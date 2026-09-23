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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Root-cause fix (2026-09-23): {@code AutonomousManagementRunner.observationFailureDetail()} surfaces
 * {@code report.observedState()} to the Human as the entire diagnostic for a BLOCKED Objective -- every
 * other {@code ObservationVerifier} in this package (see {@code GeneralWorkspaceObservationVerifier
 * .report()}, {@code AutonomyRecoveryProbeObservationVerifier.inconclusive()}) puts its actual diagnostic
 * there. {@code GitHubRepositoryObservationVerifier}'s exception handler instead hardcoded the generic
 * string "GitHub verification failed" into {@code observedState} and buried the real exception class and
 * message in {@code variance()}, a field {@code observationFailureDetail()} never reads. Every real
 * production incident that reached this catch block (including the same build-and-deliver "Metatron
 * Workforce Control Center" case across multiple resume attempts) therefore showed the Founder the exact
 * same uninformative "GitHub verification failed" text no matter what actually went wrong, making the
 * true cause undiagnosable from the one place a Human can see it.
 */
final class GitHubRepositoryObservationVerifierDiagnosticDetailTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void observedStateCarriesTheActualExceptionInsteadOfAGenericMessage() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/kelvinka38/control-center/pulls/9", exchange -> respond(exchange, 500, "{}"));
        server.start();

        GitHubRepositoryObservationVerifier verifier = new GitHubRepositoryObservationVerifier(
                HttpClient.newHttpClient(), new ObjectMapper(), base(), "test-token");
        ObservationRequirement requirement = new ObservationRequirement(
                "objective:control-center:observation:deliver:criterion:3",
                "objective:control-center", "deliver", "deliver:criterion:3",
                "kelvinka38/control-center",
                "produced workspace changes are published as a reviewable unmerged GitHub pull request",
                List.of("fresh GitHub PR metadata"), Instant.now());
        List<String> evidence = List.of(
                "github-pr:https://github.com/kelvinka38/control-center/pull/9",
                "github-general-proposal:true");

        ObservationReport report = verifier.observe(requirement, evidence, Instant.now()).orElseThrow();

        assertEquals(ObservationReport.CriterionResult.INCONCLUSIVE, report.criterionResult());
        assertTrue(report.observedState().contains("IllegalStateException"),
                "the Human-visible field must name the actual exception, not a generic message: "
                        + report.observedState());
        assertTrue(report.observedState().contains("500"),
                "the Human-visible field must include the actual HTTP status: " + report.observedState());
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
