package com.metatron.workforce.interaction.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchToolAdapterPublicKnowledgeFallbackTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void acquiresAuditableGenericCurrentVersionEvidenceWhenPrimarySearchIsIrrelevant() throws Exception {
        startFallbackServer("History of Python",
                "As of September 2026, Python 3.14.8 is the latest stable version.", 23862L);

        WebSearchToolAdapter adapter = adapter();
        ToolResult result = adapter.execute(new ToolRequest(
                "public-fallback-python", "telegram:human", WebSearchToolAdapter.CAPABILITY,
                "internet:web-search", "search",
                "Phiên bản stable mới nhất của Python hiện tại là gì? Kiểm tra nguồn hiện tại rồi trả lời.",
                List.of("read-only")));

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("PUBLIC KNOWLEDGE SEARCH RESULTS"), result.output());
        assertTrue(result.output().contains("Python 3.14.8"), result.output());
        assertEquals("https://en.wikipedia.org/?curid=23862", result.evidenceReferences().getFirst());
    }

    @Test
    void normalizesVietnameseRoleSemanticsForDomainIndependentCurrentPersonEvidence() throws Exception {
        startFallbackServer("President of Indonesia",
                "The current president is Prabowo Subianto, who assumed office on 20 October 2024.", 24150L);

        WebSearchToolAdapter adapter = adapter();
        ToolResult result = adapter.execute(new ToolRequest(
                "public-fallback-president", "telegram:human", WebSearchToolAdapter.CAPABILITY,
                "internet:web-search", "search",
                "Ai hiện đang là Tổng thống Indonesia? Kiểm tra nguồn hiện tại rồi trả lời.",
                List.of("read-only")));

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("President of Indonesia"), result.output());
        assertTrue(result.output().contains("Prabowo Subianto"), result.output());
        assertEquals("https://en.wikipedia.org/?curid=24150", result.evidenceReferences().getFirst());
        String compact = WebSearchToolAdapter.compactSearchQuery(
                "Ai hiện đang là Tổng thống Indonesia? Kiểm tra nguồn hiện tại rồi trả lời.");
        assertTrue(compact.contains("president"), compact);
        assertTrue(compact.contains("indonesia"), compact);
    }

    private WebSearchToolAdapter adapter() {
        int port = server.getAddress().getPort();
        return new WebSearchToolAdapter(HttpClient.newHttpClient(), Duration.ofSeconds(2),
                "http://127.0.0.1:" + port + "/search?q=",
                "http://127.0.0.1:" + port + "/wiki?q=");
    }

    private void startFallbackServer(String title, String snippet, long pageId) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            String rss = "<?xml version=\"1.0\"?><rss><channel>"
                    + "<item><title>Unrelated translation service</title><link>https://example.com/translate</link>"
                    + "<description>Translate documents and words.</description></item>"
                    + "</channel></rss>";
            respond(exchange, "application/rss+xml", rss);
        });
        server.createContext("/wiki", exchange -> {
            String json = "{\"query\":{\"search\":[{"
                    + "\"pageid\":" + pageId + ","
                    + "\"title\":\"" + title + "\","
                    + "\"snippet\":\"" + snippet + "\","
                    + "\"timestamp\":\"2026-09-02T00:00:00Z\"}]}}";
            respond(exchange, "application/json", json);
        });
        server.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String contentType, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
