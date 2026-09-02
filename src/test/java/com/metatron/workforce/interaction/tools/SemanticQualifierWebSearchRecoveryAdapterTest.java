package com.metatron.workforce.interaction.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticQualifierWebSearchRecoveryAdapterTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void treatsFreshnessAndAnswerTypeAsQualifiersRatherThanSubjectIdentity() {
        assertEquals(Set.of("python"), SemanticQualifierWebSearchRecoveryAdapter.subjectTokens(
                "Python latest stable version"));
        assertEquals(Set.of("visual", "studio", "code"), SemanticQualifierWebSearchRecoveryAdapter.subjectTokens(
                "Visual Studio Code latest stable version"));
    }

    @Test
    void recoversPythonVersionFromRelevantSourceWithoutLiteralQualifierWords() throws Exception {
        startServer("Python downloads", "Official Python download page.",
                "Python downloads are available now. Latest: Python 3.14.7.");
        SemanticQualifierWebSearchRecoveryAdapter adapter = adapter();

        ToolResult result = adapter.execute(request("python-recovery", "Python latest stable version"));

        assertTrue(result.success(), result.output());
        assertEquals(1, result.evidenceReferences().size());
        assertTrue(result.output().contains("Python 3.14.7"));
        assertTrue(result.output().contains("answer_shape=VERSION"));
    }

    @Test
    void recoveryIsDynamicAndNotPythonSpecific() throws Exception {
        startServer("Visual Studio Code downloads", "Official editor download page.",
                "Visual Studio Code 1.105.1 is available for supported platforms.");
        SemanticQualifierWebSearchRecoveryAdapter adapter = adapter();

        ToolResult result = adapter.execute(request("vscode-recovery",
                "Visual Studio Code latest stable version"));

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("Visual Studio Code 1.105.1"));
    }

    @Test
    void rejectsOffTopicSourceEvenWhenItContainsAValidVersionShape() throws Exception {
        startServer("Java downloads", "Current Java release.",
                "Java 25.0.1 is available for download.");
        SemanticQualifierWebSearchRecoveryAdapter adapter = adapter();

        ToolResult result = adapter.execute(request("off-topic-recovery", "Python latest stable version"));

        assertFalse(result.success());
        assertTrue(result.output().contains("no_answer_shaped_relevant_results"), result.output());
    }

    @Test
    void rejectsSubjectRelevantSourceWhenItDoesNotContainTheRequestedAnswerShape() throws Exception {
        startServer("Python downloads", "Official Python project page.",
                "Python is a programming language with documentation and downloads.");
        SemanticQualifierWebSearchRecoveryAdapter adapter = adapter();

        ToolResult result = adapter.execute(request("missing-answer-recovery", "Python latest stable version"));

        assertFalse(result.success());
        assertTrue(result.output().contains("no_answer_shaped_relevant_results"), result.output());
    }

    @Test
    void toolFabricUsesRecoveryOnlyAfterThePrimaryCapabilityFails() throws Exception {
        startServer("Python downloads", "Official Python download page.",
                "Python downloads are available now. Latest: Python 3.14.7.");
        ToolAdapter failedPrimary = new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }
            @Override public ToolResult execute(ToolRequest request) {
                return ToolResult.failure(request, "primary_search_exhausted");
            }
        };
        SemanticQualifierWebSearchRecoveryAdapter recovery = adapter();
        DefaultToolFabric fabric = new DefaultToolFabric(List.of(failedPrimary, recovery));

        ToolResult result = fabric.execute(request("fabric-recovery", "Python latest stable version"));

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("SEMANTIC QUALIFIER WEB RECOVERY"));
    }

    @Test
    void versionAnswerShapeRequiresAConcreteVersionObservation() {
        assertTrue(SemanticQualifierWebSearchRecoveryAdapter.answersVersionRequirement("Python 3.14.7"));
        assertTrue(SemanticQualifierWebSearchRecoveryAdapter.answersVersionRequirement("VS Code v1.105.1"));
        assertFalse(SemanticQualifierWebSearchRecoveryAdapter.answersVersionRequirement("Python latest release"));
    }

    private SemanticQualifierWebSearchRecoveryAdapter adapter() {
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/search?q=";
        return new SemanticQualifierWebSearchRecoveryAdapter(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
                Duration.ofSeconds(2), endpoint, true);
    }

    private ToolRequest request(String id, String query) {
        return new ToolRequest(id, "human:test", WebSearchToolAdapter.CAPABILITY,
                "internet:web-search", "search", query, List.of("read-only"));
    }

    private void startServer(String title, String description, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/search", exchange -> {
            String source = "http://127.0.0.1:" + port + "/source";
            String rss = "<?xml version=\"1.0\"?><rss><channel>"
                    + "<item><title>" + title + "</title><link>" + source + "</link>"
                    + "<description>" + description + "</description></item>"
                    + "</channel></rss>";
            byte[] bytes = rss.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.createContext("/source", exchange -> {
            String html = "<html><body>" + body + "</body></html>";
            byte[] bytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
    }
}
