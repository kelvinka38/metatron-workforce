package com.metatron.workforce.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.management.RepositoryPullRequestAutonomousCapability;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GitHubRepositoryObservationVerifierTest {
    private static final String PROBE = "0123456789abcdef";
    private static final String BRANCH = "autonomy/gs2-" + PROBE;
    private static final String MAIN = "before\nGS2_AUTONOMOUS_PROBE=UNSET\nafter\n";
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void freshObservationPassesOnlyExactSentinelReplacementOnOpenUnmergedPr() throws Exception {
        start(MAIN.replace("GS2_AUTONOMOUS_PROBE=UNSET", "GS2_AUTONOMOUS_PROBE=" + PROBE));
        GitHubRepositoryObservationVerifier verifier = verifier();

        ObservationReport report = verifier.observe(requirement(), executionEvidence(), Instant.now()).orElseThrow();

        assertEquals(ObservationReport.CriterionResult.PASS, report.criterionResult());
        assertEquals(ObservationReport.Quality.HIGH, report.quality());
        assertTrue(report.evidenceReferences().contains("github-pr-observation-exact-bounded-repair:true"));
        assertTrue(report.evidenceReferences().contains("github-pr-observation-main-fixture-intact:true"));
        assertTrue(report.evidenceReferences().contains("github-pr-observation-branch-correlated:true"));
    }

    @Test
    void freshObservationRejectsExtraChangeEvenWhenPrIsOpenAndAllowedFileOnly() throws Exception {
        String changed = MAIN.replace("GS2_AUTONOMOUS_PROBE=UNSET", "GS2_AUTONOMOUS_PROBE=" + PROBE)
                + "unauthorized-extra-change\n";
        start(changed);
        GitHubRepositoryObservationVerifier verifier = verifier();

        ObservationReport report = verifier.observe(requirement(), executionEvidence(), Instant.now()).orElseThrow();

        assertEquals(ObservationReport.CriterionResult.FAIL, report.criterionResult());
        assertTrue(report.variance().contains("exactBoundedRepair=false"));
    }

    @Test
    void freshObservationRejectsMissingProbeCorrelationEvidence() throws Exception {
        start(MAIN.replace("GS2_AUTONOMOUS_PROBE=UNSET", "GS2_AUTONOMOUS_PROBE=" + PROBE));
        GitHubRepositoryObservationVerifier verifier = verifier();
        List<String> evidence = List.of(
                "github-pr:https://github.com/kelvinka38/metatron-workforce/pull/108",
                "github-merge-performed:false",
                "github-branch:" + BRANCH);

        ObservationReport report = verifier.observe(requirement(), evidence, Instant.now()).orElseThrow();

        assertEquals(ObservationReport.CriterionResult.FAIL, report.criterionResult());
        assertTrue(report.variance().contains("probe=null"));
    }

    private GitHubRepositoryObservationVerifier verifier() {
        return new GitHubRepositoryObservationVerifier(HttpClient.newHttpClient(), new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-token");
    }

    private ObservationRequirement requirement() {
        return new ObservationRequirement(
                "REQ-PR", "OBJ-PR", "step_1", "criterion_1",
                RepositoryPullRequestAutonomousCapability.ALLOWED_REPOSITORY,
                "A pull request is opened for the approved repair without being merged.",
                List.of("fresh pull request state", "modified file scope", "merge status"), Instant.now());
    }

    private List<String> executionEvidence() {
        return List.of(
                "github-pr:https://github.com/kelvinka38/metatron-workforce/pull/108",
                "github-gs2-probe:" + PROBE,
                "github-merge-performed:false",
                "github-branch:" + BRANCH);
    }

    private void start(String headContent) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> handle(exchange, headContent));
        server.start();
    }

    private void handle(HttpExchange exchange, String headContent) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        if (path.equals("/repos/kelvinka38/metatron-workforce/pulls/108")) {
            respond(exchange, 200, "{\"state\":\"open\",\"merged\":false,\"merged_at\":null,"
                    + "\"base\":{\"ref\":\"main\"},\"head\":{\"ref\":\"" + BRANCH + "\"}}");
            return;
        }
        if (path.equals("/repos/kelvinka38/metatron-workforce/pulls/108/files")) {
            respond(exchange, 200, "[{\"filename\":\"" + RepositoryPullRequestAutonomousCapability.ALLOWED_PATH + "\"}]");
            return;
        }
        String contentsPath = "/repos/kelvinka38/metatron-workforce/contents/"
                + RepositoryPullRequestAutonomousCapability.ALLOWED_PATH;
        if (path.equals(contentsPath)) {
            String ref = query == null ? "" : URLDecoder.decode(query.replaceFirst("^ref=", ""), StandardCharsets.UTF_8);
            String content = "main".equals(ref) ? MAIN : headContent;
            respond(exchange, 200, "{\"content\":\"" + Base64.getEncoder().encodeToString(
                    content.getBytes(StandardCharsets.UTF_8)) + "\"}");
            return;
        }
        respond(exchange, 404, "{}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
