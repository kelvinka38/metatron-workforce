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
    void fetchedSourceExcerptCanEstablishRequirementRelevance() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/search", exchange -> {
            String source = "http://127.0.0.1:" + port + "/python";
            String rss = "<?xml version=\"1.0\"?><rss><channel>"
                    + "<item><title>Python downloads</title><link>" + source + "</link>"
                    + "<description>Official Python download page.</description></item>"
                    + "</channel></rss>";
            byte[] bytes = rss.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.createContext("/python", exchange -> {
            String html = "<html><body>Latest stable Python version: Python 3.14.2.</body></html>";
            byte[] bytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();

        String endpoint = "http://127.0.0.1:" + port + "/search?q=";
        WebSearchToolAdapter adapter = new WebSearchToolAdapter(
                HttpClient.newHttpClient(), Duration.ofSeconds(2), endpoint);

        ToolResult result = adapter.execute(new ToolRequest(
                "web-python", "telegram:human", WebSearchToolAdapter.CAPABILITY,
                "internet:web-search", "search", "Python stable version", java.util.List.of("read-only")));

        assertTrue(result.success(), result.output());
        assertEquals("http://127.0.0.1:" + port + "/python", result.evidenceReferences().getFirst());
        assertTrue(result.output().contains("source_excerpt=Latest stable Python version: Python 3.14.2."));
    }

    @Test
    void compactsMultilingualCurrentInformationQueryToSubjectTerms() {
        assertEquals("version stable python",
                WebSearchToolAdapter.compactSearchQuery(
                        "version stable latest của Python current là gì? check nguồn current rồi answer"));
        assertEquals("president indonesia",
                WebSearchToolAdapter.compactSearchQuery(
                        "Ai current đang là president Indonesia? check nguồn current rồi answer"));
    }

    @Test
    void groundedSearchQueriesContributeToRelevanceContext() throws Exception {
        var metadata = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"webSearchQueries\":[\"latest stable Python version\",\"Python releases\"]}");
        assertEquals("latest stable Python version Python releases",
                WebSearchToolAdapter.groundingQueryText(metadata));
        assertTrue(WebSearchToolAdapter.materiallyRelevant(
                "version stable Python", "Python 3.14.2 " + WebSearchToolAdapter.groundingQueryText(metadata)));
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
                "internet:web-search", "search", "Indonesia president", java.util.List.of("read-only")));

        assertTrue(!result.success());
        assertEquals("web_search_no_relevant_results", result.output());
    }

    @Test
    void canonicalizesVietnameseHoChiMinhWeatherLocation() {
        assertEquals("Ho Chi Minh City", WebSearchToolAdapter.weatherLocation(
                "Thời tiết hiện tại ở Thành phố Hồ Chí Minh thế nào? Kiểm tra dữ liệu mới và nêu nguồn."));
    }

    @Test
    void stripsEnglishWeatherRetrievalInstructionsFromLocation() {
        assertEquals("Berlin", WebSearchToolAdapter.weatherLocation(
                "current weather in Berlin using fresh data and cite sources"));
    }

    @Test
    void bindsSemanticBitcoinValuationToStructuredMarketSources() {
        assertTrue(WebSearchToolAdapter.bitcoinStructuredSourceEligible(
                "Retrieve the current Bitcoin valuation in USD and VND and cite the source."));
        assertTrue(WebSearchToolAdapter.bitcoinStructuredSourceEligible(
                "Giá Bitcoin hiện tại khoảng bao nhiêu USD và VND?"));
        assertTrue(!WebSearchToolAdapter.bitcoinStructuredSourceEligible(
                "Explain Bitcoin consensus architecture."));
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
