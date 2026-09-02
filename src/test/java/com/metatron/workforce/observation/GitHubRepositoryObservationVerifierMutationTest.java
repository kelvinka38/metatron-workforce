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
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubRepositoryObservationVerifierMutationTest {
    private static final String PROBE = "0123456789abcdef";
    private static final String HEAD = "autonomy/gs2-" + PROBE;
    private static final String PATH = "docs/AUTONOMY_CLOSURE/CURRENT_STATE_AND_GAP_MATRIX.md";
    private static final String MAIN = "# FINAL ACCEPTED AUTONOMY-CLOSURE BASELINE\n"
            + "General autonomy verdict: ACCEPTED_L10\n"
            + "Durable versioned DAG\n"
            + "GS2_AUTONOMOUS_PROBE=UNSET\n";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void passesOnlyExactSentinelMutationOnCurrentAcceptedBaseline() throws Exception {
        start(MAIN.replace("GS2_AUTONOMOUS_PROBE=UNSET", "GS2_AUTONOMOUS_PROBE=" + PROBE));
        ObservationReport report = verifier().observe(requirement(), evidence(PROBE), Instant.now()).orElseThrow();
        assertEquals(ObservationReport.CriterionResult.PASS, report.criterionResult());
        assertEquals(ObservationReport.Quality.HIGH, report.quality());
        assertTrue(report.evidenceReferences().contains("github-pr-observation-exact-bounded-mutation:true"));
        assertTrue(report.evidenceReferences().contains("github-pr-observation-probe-bound:true"));
    }

    @Test
    void failsClosedWhenBranchContainsAnyExtraEdit() throws Exception {
        String expected = MAIN.replace("GS2_AUTONOMOUS_PROBE=UNSET", "GS2_AUTONOMOUS_PROBE=" + PROBE);
        start(expected + "UNAUTHORIZED_EXTRA_EDIT\n");
        ObservationReport report = verifier().observe(requirement(), evidence(PROBE), Instant.now()).orElseThrow();
        assertEquals(ObservationReport.CriterionResult.FAIL, report.criterionResult());
        assertTrue(report.evidenceReferences().contains("github-pr-observation-exact-bounded-mutation:false"));
    }

    @Test
    void failsClosedWhenExecutionProbeDoesNotMatchObjectiveBranch() throws Exception {
        start(MAIN.replace("GS2_AUTONOMOUS_PROBE=UNSET", "GS2_AUTONOMOUS_PROBE=" + PROBE));
        ObservationReport report = verifier().observe(requirement(), evidence("fedcba9876543210"), Instant.now()).orElseThrow();
        assertEquals(ObservationReport.CriterionResult.FAIL, report.criterionResult());
        assertTrue(report.evidenceReferences().contains("github-pr-observation-probe-bound:false"));
    }

    private GitHubRepositoryObservationVerifier verifier() {
        return new GitHubRepositoryObservationVerifier(
                HttpClient.newHttpClient(), new ObjectMapper(), base(), "test-token");
    }

    private ObservationRequirement requirement() {
        return new ObservationRequirement(
                "objective:point4:observation:step_1:criterion:1",
                "objective:point4", "step_1", "step_1:criterion:1",
                "kelvinka38/metatron-workforce",
                "A reviewable unmerged PR modifies only the approved gap-matrix path.",
                List.of("fresh GitHub PR metadata and exact changed content"), Instant.now());
    }

    private List<String> evidence(String probe) {
        return List.of(
                "github-pr:https://github.com/kelvinka38/metatron-workforce/pull/108",
                "github-gs2-probe:" + probe,
                "github-merge-performed:false");
    }

    private void start(String branchContent) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/kelvinka38/metatron-workforce/pulls/108/files", exchange -> respond(exchange, 200,
                "[{\"filename\":\"" + PATH + "\"}]"));
        server.createContext("/repos/kelvinka38/metatron-workforce/pulls/108", exchange -> respond(exchange, 200,
                "{\"state\":\"open\",\"merged\":false,\"merged_at\":null,"
                        + "\"base\":{\"ref\":\"main\"},\"head\":{\"ref\":\"" + HEAD + "\"}}"));
        server.createContext("/repos/kelvinka38/metatron-workforce/contents/" + PATH, exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String content = query != null && query.contains("ref=main") ? MAIN : branchContent;
            respond(exchange, 200, "{\"content\":\"" + Base64.getEncoder().encodeToString(
                    content.getBytes(StandardCharsets.UTF_8)) + "\"}");
        });
        server.start();
    }

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
