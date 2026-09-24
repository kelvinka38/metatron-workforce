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
 * Production incident (2026-09-24, case build-and-deliver "Metatron Workforce Control Center"): after the
 * 404 read-lag fix, the published-PR criterion still went observation-inconclusive, with all three outer
 * Observation attempts consumed within ~9 seconds of DELIVER -- a fast non-404 failure, which the verifier
 * never retried. These tests prove (1) a transient GitHub rate-limit 403 and (2) a 5xx right after the
 * proposal-write burst are absorbed by the bounded retry, and (3) a genuine non-transient 403 still fails
 * immediately, now naming the endpoint and GitHub's own message instead of a bare status code.
 */
final class GitHubRepositoryObservationVerifierTransientFailureTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void transientSecondaryRateLimitIsRetriedInsteadOfFailingObservation() throws Exception {
        AtomicInteger pullReads = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/kelvinka38/control-center/pulls/9", exchange -> {
            if (pullReads.getAndIncrement() == 0) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                respond(exchange, 403, "{\"message\":\"You have exceeded a secondary rate limit.\"}");
                return;
            }
            respond(exchange, 200, pull());
        });
        passingCommitAndFiles();
        server.start();

        ObservationReport report = verifier().observe(requirement(), evidence(), Instant.now()).orElseThrow();

        assertEquals(ObservationReport.CriterionResult.PASS, report.criterionResult());
        assertEquals(2, pullReads.get(), "the rate-limited read must have been retried once before succeeding");
    }

    @Test
    void transientServerErrorIsRetriedInsteadOfFailingObservation() throws Exception {
        AtomicInteger filesReads = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/kelvinka38/control-center/pulls/9", exchange -> respond(exchange, 200, pull()));
        server.createContext("/repos/kelvinka38/control-center/git/commits/" + headSha(), exchange ->
                respond(exchange, 200, "{\"parents\":[{\"sha\":\"" + baseSha() + "\"}]}"));
        server.createContext("/repos/kelvinka38/control-center/pulls/9/files", exchange -> {
            if (filesReads.getAndIncrement() == 0) { respond(exchange, 502, "{\"message\":\"Server Error\"}"); return; }
            respond(exchange, 200, "[{\"filename\":\"index.html\"}]");
        });
        server.start();

        ObservationReport report = verifier().observe(requirement(), evidence(), Instant.now()).orElseThrow();

        assertEquals(ObservationReport.CriterionResult.PASS, report.criterionResult());
        assertEquals(2, filesReads.get(), "the transient 502 must have been retried once before succeeding");
    }

    @Test
    void genuinePermissionDenialFailsImmediatelyWithADiagnosableMessage() throws Exception {
        AtomicInteger pullReads = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/kelvinka38/control-center/pulls/9", exchange -> {
            pullReads.incrementAndGet();
            respond(exchange, 403, "{\"message\":\"Resource not accessible by personal access token\"}");
        });
        server.start();

        ObservationReport report = verifier().observe(requirement(), evidence(), Instant.now()).orElseThrow();

        assertEquals(ObservationReport.CriterionResult.INCONCLUSIVE, report.criterionResult());
        assertEquals(1, pullReads.get(), "a non-transient 403 must not be retried");
        assertTrue(report.observedState().contains("HTTP 403")
                        && report.observedState().contains("/repos/kelvinka38/control-center/pulls/9")
                        && report.observedState().contains("Resource not accessible by personal access token"),
                "the blocker must name the status, the endpoint and GitHub's own message: " + report.observedState());
    }

    private void passingCommitAndFiles() {
        server.createContext("/repos/kelvinka38/control-center/git/commits/" + headSha(), exchange ->
                respond(exchange, 200, "{\"parents\":[{\"sha\":\"" + baseSha() + "\"}]}"));
        server.createContext("/repos/kelvinka38/control-center/pulls/9/files", exchange ->
                respond(exchange, 200, "[{\"filename\":\"index.html\"}]"));
    }

    private GitHubRepositoryObservationVerifier verifier() {
        return new GitHubRepositoryObservationVerifier(
                HttpClient.newHttpClient(), new ObjectMapper(), base(), "test-token");
    }

    private ObservationRequirement requirement() {
        return new ObservationRequirement(
                "objective:control-center:observation:deliver:criterion:3",
                "objective:control-center", "deliver", "deliver:criterion:3",
                "repository:kelvinka38/control-center",
                "reviewable unmerged GitHub pull request exists for the committed work product",
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
