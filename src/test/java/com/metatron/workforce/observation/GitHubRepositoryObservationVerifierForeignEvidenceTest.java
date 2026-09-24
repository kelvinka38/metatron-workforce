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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production incident (2026-09-24, case build-and-deliver "Metatron Workforce Control Center"), made
 * visible by the autonomy_observation_blocked log line: "GitHub verification failed: SecurityException:
 * general proposal evidence repository does not match Observation target" -- although the real proposal
 * PR existed in the target repository. The Objective-wide evidence also carries references that are not
 * this Objective's own publication (e.g. the Worker's runtime-constitution/standing evidence naming a PR
 * the same Worker authored for an earlier Objective in another repository), and Observation bound itself
 * to the FIRST github-pr reference it found.
 */
final class GitHubRepositoryObservationVerifierForeignEvidenceTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void aForeignPullRequestReferenceEarlierInTheEvidenceNeverDecidesObservation() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/kelvinka38/control-center/pulls/3", exchange -> respond(exchange, 200, pull()));
        server.createContext("/repos/kelvinka38/control-center/git/commits/" + headSha(), exchange ->
                respond(exchange, 200, "{\"parents\":[{\"sha\":\"" + baseSha() + "\"}]}"));
        server.createContext("/repos/kelvinka38/control-center/pulls/3/files", exchange ->
                respond(exchange, 200, "[{\"filename\":\"index.html\"}]"));
        server.start();

        List<String> evidence = new ArrayList<>();
        // Earlier, unrelated references (e.g. Worker standing from a prior Objective's PR elsewhere).
        evidence.add("github-pr:https://github.com/kelvinka38/metatron-workforce/pull/521");
        evidence.add("github-branch:metatron/objective-eb7f5ea3a335-871e5aba");
        evidence.add("github-base-branch:develop");
        evidence.addAll(ownPublication());

        ObservationReport report = verifier().observe(requirement(), evidence, Instant.now()).orElseThrow();

        assertEquals(ObservationReport.CriterionResult.PASS, report.criterionResult(),
                "Observation must bind to this Objective's own PR in the target repository: "
                        + report.observedState() + " / " + report.variance());
    }

    @Test
    void noPullRequestInTheTargetRepositoryStillFailsClosedAndNamesBothRepositories() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();

        List<String> evidence = List.of(
                "github-general-proposal:true",
                "github-pr:https://github.com/kelvinka38/metatron-workforce/pull/521",
                "github-merge-performed:false");

        ObservationReport report = verifier().observe(requirement(), evidence, Instant.now()).orElseThrow();

        assertEquals(ObservationReport.CriterionResult.INCONCLUSIVE, report.criterionResult());
        assertTrue(report.observedState().contains("target=kelvinka38/control-center")
                        && report.observedState().contains("kelvinka38/metatron-workforce"),
                "the blocker must name the target and the repositories actually found: " + report.observedState());
    }

    private List<String> ownPublication() {
        return List.of(
                "github-general-proposal:true",
                "github-pr:https://github.com/kelvinka38/control-center/pull/3",
                "github-pr-number:3",
                "github-repository:kelvinka38/control-center",
                "github-base-branch:main",
                "github-source-sha:" + baseSha(),
                "github-branch:metatron/objective-control-center",
                "github-remote-commit:" + headSha(),
                "github-changed-path:index.html",
                "github-merge-performed:false");
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
