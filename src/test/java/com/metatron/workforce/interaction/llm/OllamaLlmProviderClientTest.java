package com.metatron.workforce.interaction.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves OllamaLlmProviderClient's real HTTP request/response handling against a genuine local HTTP
 * server (JDK built-in, no new test dependency) -- not just a hand-constructed LlmResponse -- since
 * this client exists specifically to close a real production gap (see its class Javadoc): the
 * planning layer previously had no self-hosted fallback at all.
 */
class OllamaLlmProviderClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void completeParsesARealOllamaGenerateResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> respond(exchange, 200,
                "{\"response\":\"QWEN_READY\",\"prompt_eval_count\":17,\"eval_count\":42}"));
        server.start();
        OllamaLlmProviderClient client = new OllamaLlmProviderClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), HttpClient.newHttpClient(), new ObjectMapper());

        LlmResponse response = client.complete(new LlmRequest(
                LlmProvider.OLLAMA, "qwen3:8b", "You are a planner.", "Return only: QWEN_READY"));

        assertEquals(LlmProvider.OLLAMA, response.provider());
        assertEquals("qwen3:8b", response.model());
        assertEquals("QWEN_READY", response.text());
        assertEquals(17L, response.usage().inputTokens());
        assertEquals(42L, response.usage().outputTokens());

    }

    @Test
    void completeThrowsOnNon2xxStatus() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> respond(exchange, 500, "{\"error\":\"model not found\"}"));
        server.start();
        OllamaLlmProviderClient client = new OllamaLlmProviderClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), HttpClient.newHttpClient(), new ObjectMapper());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> client.complete(
                new LlmRequest(LlmProvider.OLLAMA, "qwen3:8b", "", "hello")));
        assertTrue(failure.getMessage().contains("ollama_request_failed"));
    }

    @Test
    void completeThrowsOnBlankResponseField() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> respond(exchange, 200, "{\"response\":\"\"}"));
        server.start();
        OllamaLlmProviderClient client = new OllamaLlmProviderClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), HttpClient.newHttpClient(), new ObjectMapper());

        assertThrows(IllegalStateException.class, () -> client.complete(
                new LlmRequest(LlmProvider.OLLAMA, "qwen3:8b", "", "hello")));
    }

    @Test
    void providerReturnsOllama() {
        OllamaLlmProviderClient client = new OllamaLlmProviderClient(
                "http://127.0.0.1:1", HttpClient.newHttpClient(), new ObjectMapper());
        assertEquals(LlmProvider.OLLAMA, client.provider());
    }

    @Test
    void completeRejectsRequestForADifferentProvider() {
        OllamaLlmProviderClient client = new OllamaLlmProviderClient(
                "http://127.0.0.1:1", HttpClient.newHttpClient(), new ObjectMapper());
        assertThrows(IllegalArgumentException.class, () -> client.complete(
                new LlmRequest(LlmProvider.GOOGLE, "gemini-3.7-flash", "", "hello")));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
