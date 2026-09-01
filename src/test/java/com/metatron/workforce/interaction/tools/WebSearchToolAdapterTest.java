package com.metatron.workforce.interaction.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchToolAdapterTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void parsesSearchResultsAndAttributesEvidence() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            String rss = "<?xml version=\"1.0\"?><rss><channel>"
                    + "<item><title>Example result</title><link>https://example.com/a</link>"
                    + "<description>Example snippet &amp; evidence.</description></item>"
                    + "</channel></rss>";
            byte[] bytes = rss.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/search?q=";
        WebSearchToolAdapter adapter = new WebSearchToolAdapter(
                HttpClient.newHttpClient(), Duration.ofSeconds(2), endpoint);

        ToolResult result = adapter.execute(new ToolRequest(
                "web-1", "telegram:human", WebSearchToolAdapter.CAPABILITY,
                "internet:web-search", "search", "example result", java.util.List.of("read-only")));

        assertTrue(result.success(), result.output());
        assertEquals("https://example.com/a", result.evidenceReferences().getFirst());
        assertTrue(result.output().contains("Example result"));
        assertTrue(result.output().contains("Example snippet & evidence."));
    }

    @Test
    void rejectsOffTopicRssResultsAsRequirementEvidence() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            String rss = "<?xml version=\"1.0\"?><rss><channel>"
                    + "<item><title>Language translation service</title><link>https://example.com/translate</link>"
                    + "<description>Translate words and documents between languages.</description></item>"
                    + "</channel></rss>";
            byte[] bytes = rss.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/search?q=";
        WebSearchToolAdapter adapter = new WebSearchToolAdapter(
                HttpClient.newHttpClient(), Duration.ofSeconds(2), endpoint);

        ToolResult result = adapter.execute(new ToolRequest(
                "web-off-topic", "telegram:human", WebSearchToolAdapter.CAPABILITY,
                "internet:web-search", "search", "USD VND exchange rate", java.util.List.of("read-only")));

        assertTrue(!result.success());
        assertEquals("web_search_no_relevant_results", result.output());
    }

    @Test
    void treatsExplicitInsufficientGroundingMarkerAsFailure() {
        assertTrue(WebSearchToolAdapter.looksLikeInsufficientAnswer("INSUFFICIENT_EVIDENCE"));
    }

    @Test
    void rejectsBlankQueries() {
        WebSearchToolAdapter adapter = new WebSearchToolAdapter();
        ToolResult result = adapter.execute(new ToolRequest(
                "web-blank", "telegram:human", WebSearchToolAdapter.CAPABILITY,
                "internet:web-search", "search", "  ", java.util.List.of("read-only")));

        assertTrue(!result.success());
        assertEquals("web_search_query_empty", result.output());
    }
}
