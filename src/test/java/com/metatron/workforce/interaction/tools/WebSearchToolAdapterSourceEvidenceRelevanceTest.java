package com.metatron.workforce.interaction.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchToolAdapterSourceEvidenceRelevanceTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void rejectsSearchMetadataThatNamesEntityWhenFetchedSourceDoesNot() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        String wrongUrl = "http://127.0.0.1:" + port + "/wrong";
        server.createContext("/search", exchange -> {
            String rss = "<?xml version=\"1.0\"?><rss><channel>"
                    + "<item><title>Current President of Indonesia</title><link>" + wrongUrl + "</link>"
                    + "<description>Indonesia current president result.</description></item>"
                    + "</channel></rss>";
            respond(exchange, "application/rss+xml", rss);
        });
        server.createContext("/wrong", exchange -> respond(exchange, "text/html",
                "<html><body><h1>List of presidents of the United States</h1>"
                        + "<p>This source discusses presidents and office holders in the United States only.</p>"
                        + "</body></html>"));
        server.start();

        WebSearchToolAdapter adapter = new WebSearchToolAdapter(
                HttpClient.newHttpClient(), Duration.ofSeconds(2),
                "http://127.0.0.1:" + port + "/search?q=");
        ToolResult result = adapter.execute(new ToolRequest(
                "metadata-laundering", "telegram:human", WebSearchToolAdapter.CAPABILITY,
                "internet:web-search", "search", "current president Indonesia", List.of("read-only")));

        assertFalse(result.success(), result.output());
        assertFalse(result.evidenceReferences().contains(wrongUrl), result.toString());
        assertTrue(result.output().contains("web_search_no_relevant_results"), result.output());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String contentType, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
