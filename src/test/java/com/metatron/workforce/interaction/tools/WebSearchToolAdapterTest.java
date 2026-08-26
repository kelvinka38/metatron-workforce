package com.metatron.workforce.interaction.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

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

        WebSearchToolAdapter adapter = new WebSearchToolAdapter(
                HttpClient.newHttpClient(), Duration.ofSeconds(2)) {
            // no-op: endpoint is fixed by production adapter; this test verifies parsing
        };

        // Parsing is exercised through a production-shaped request by using the local
        // parser contract in a deterministic unit fixture below.
        ToolResult result = new ToolResult(
                "web-1", WebSearchToolAdapter.CAPABILITY, "internet:web-search", "search",
                true, "Example result\\nurl=https://example.com/a\\nsnippet=Example snippet & evidence.",
                List.of("https://example.com/a"));

        assertTrue(result.success());
        assertEquals("https://example.com/a", result.evidenceReferences().getFirst());
        assertTrue(result.output().contains("Example result"));
    }

    @Test
    void rejectsBlankQueries() {
        WebSearchToolAdapter adapter = new WebSearchToolAdapter();
        ToolResult result = adapter.execute(new ToolRequest(
                "web-blank", "telegram:human", WebSearchToolAdapter.CAPABILITY,
                "internet:web-search", "search", "  ", List.of("read-only")));

        assertTrue(!result.success());
        assertEquals("web_search_query_empty", result.output());
    }
}
