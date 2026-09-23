package com.metatron.workforce.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production incident (2026-09-23), same case build-and-deliver "Metatron Workforce Control Center": the
 * general-workspace DELIVER step published a real reviewable PR (workspace.github.pr.publish PASS), but
 * Observation's own independent fresh GitHub reads of that just-created PR -- issued within seconds, at
 * Observation's own bounded retry cadence -- hit GitHub's read-after-write propagation lag and 404'd,
 * exhausting all 3 outer Observation attempts and permanently blocking an Objective that had genuinely
 * completed. These tests prove: (1) a transient 404 on the PR object, and (2) on the PR's file-diff
 * endpoint specifically, are both absorbed by a short bounded retry instead of failing the whole
 * Observation immediately; (3) a genuinely nonexistent PR still fails (INCONCLUSIVE) instead of retrying
 * forever.
 */
final class GitHubRepositoryObservationVerifierReadLagTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void transientNotFoundOnThePullRequestItselfIsAbsorbedInsteadOfFailingObservation() throws Exception {
        AtomicInteger pullReads = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/kelvinka38/control-center/pulls/9", exchange -> {
            if (pullReads.getAndIncrement() == 0) { respond(exchange, 404, "{}"); return; }
            respond(exchange, 200, pull());
        });
        server.createContext("/repos/kelvinka38/control-center/git/commits/" + headSha(), exchange ->
                respond(exchange, 200, "{\"parents\":[{\"sha\":\"" + baseSha() + "\"}]}"));
        server.createContext("/repos/kelvinka38/control-center/pulls/9/files", exchange ->
                respond(exchange, 200, "[{\"filename\":\"index.html\"}]"));
        server.start();

        ObservationReport report = verifier().observe(requirement(), evidence(), Instant.now()).orElseThrow();

        assertEquals(ObservationReport.CriterionResult.PASS, report.criterionResult());
        assertEquals(2, pullReads.get(), "the transient 404 must have been retried exactly once before succeeding");
    }

    @Test
    void transientNotFoundOnTheFilesEndpointIsAbsorbedInsteadOfFailingObservation() throws Exception {
        AtomicInteger filesReads = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/kelvinka38/control-center/pulls/9", exchange -> respond(exchange, 200, pull()));
        server.createContext("/repos/kelvinka38/control-center/git/commits/" + headSha(), exchange ->
                respond(exchange, 200, "{\"parents\":[{\"sha\":\"" + baseSha() + "\"}]}"));
        server.createContext("/repos/kelvinka38/control-center/pulls/9/files", exchange -> {
            if (filesReads.getAndIncrement() == 0) { respond(exchange, 404, "{}"); return; }
            respond(exchange, 200, "[{\"filename\":\"index.html\"}]");
        });
        server.start();

        ObservationReport report = verifier().observe(requirement(), evidence(), Instant.now()).orElseThrow();

        assertEquals(ObservationReport.CriterionResult.PASS, report.criterionResult());
        assertEquals(2, filesReads.get(), "the transient 404 on the files endpoint must have been retried");
    }

    @Test
    void aGenuinelyNonexistentPullRequestStillFailsClosedInsteadOfRetryingForever() throws Exception {
        AtomicInteger pullReads = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/kelvinka38/control-center/pulls/9", exchange -> {
            pullReads.incrementAndGet();
            respond(exchange, 404, "{}");
        });
        server.start();

        ObservationReport report = verifier().observe(requirement(), evidence(), Instant.now()).orElseThrow();

        assertEquals(ObservationReport.CriterionResult.INCONCLUSIVE, report.criterionResult());
        assertEquals(4, pullReads.get(), "exactly the initial attempt plus the 3 bounded retries, then give up");
    }

    private GitHubRepositoryObservationVerifier verifier() {
        return new GitHubRepositoryObservationVerifier(
                HttpClient.newHttpClient(), new ObjectMapper(), base(), "test-token");
    }

    private ObservationRequirement requirement() {
        return new ObservationRequirement(
                "objective:control-center:observation:deliver:criterion:3",
                "objective:control-center", "deliver", "deliver:criterion:3",
                "kelvinka38/control-center",
                "produced workspace changes are published as a reviewable unmerged GitHub pull request",
                List.of("fresh GitHub PR metadata"), Instant.now());
    }

    private List<String> evidence() {
        return List.of(
                "github-pr:https://github.com/kelvinka38/control-center/pull/9",
                "github-general-proposal:true",
                "github-base-branch:main",
                "github-branch:metatron/objective-control-center",
                "github-source-sha:" + baseSha(),
                "github-remote-commit:" + headSha(),
                "github-changed-path:index.html",
                "github-merge-performed:false");
    }

    private String pull() {
        return "{\"state\":\"open\",\"merged\":false,\"merged_at\":null,"
                + "\"base\":{\"ref\":\"main\"},"
                + "\"head\":{\"ref\":\"metatron/objective-control-center\",\"sha\":\"" + headSha() + "\"}}";
    }

    private static String headSha() { return "b".repeat(40); }
    private static String baseSha() { return "a".repeat(40); }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
