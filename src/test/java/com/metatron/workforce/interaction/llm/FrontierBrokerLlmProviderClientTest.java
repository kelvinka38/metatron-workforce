package com.metatron.workforce.interaction.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontierBrokerLlmProviderClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void humanRequestPreservesOriginAndParsesBrokerResponse() throws Exception {
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/frontier/complete", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            captured.set(mapper.readTree(exchange.getRequestBody()));
            byte[] response = """
                    {"provider":"OPENAI","model":"frontier-model","text":"frontier answer",\
                    "requestReference":"provider-ref","usage":{"inputTokens":12,"outputTokens":5,"totalTokens":17}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            FrontierBrokerLlmProviderClient client = new FrontierBrokerLlmProviderClient(
                    LlmProvider.OPENAI,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "internal-token",
                    HttpClient.newHttpClient(),
                    mapper);
            LlmResponse result = client.complete(new LlmRequest(
                    LlmProvider.OPENAI, "frontier-model", "system", "input",
                    "REQ-H", "CASE-H", "semantic-primary", "", FrontierCallBudget.legacyUnbounded(),
                    "HUMAN", "human", "", "", "", "", ""));

            assertEquals("Bearer internal-token", authorization.get());
            assertEquals("HUMAN", captured.get().path("provenance").path("originType").asText());
            assertEquals("human", captured.get().path("provenance").path("actorId").asText());
            assertEquals("frontier answer", result.text());
            assertEquals(12, result.usage().inputTokens());
            assertEquals(5, result.usage().outputTokens());
            assertEquals("frontier-broker", result.providerTelemetry().get("transport"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void workerOriginIsDeniedBeforeAnyBrokerNetworkCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/frontier/complete", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            FrontierBrokerLlmProviderClient client = new FrontierBrokerLlmProviderClient(
                    LlmProvider.OPENAI,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "internal-token",
                    HttpClient.newHttpClient(), mapper);
            SecurityException denied = assertThrows(SecurityException.class, () -> client.complete(new LlmRequest(
                    LlmProvider.OPENAI, "frontier-model", "system", "input",
                    "REQ-W", "", "worker-intelligence", "", FrontierCallBudget.legacyUnbounded(),
                    "WORKER", "WORKER-1", "WORKER-1", "OBJ-1", "ASG-1", "STEP-1", "ATT-1")));
            assertTrue(denied.getMessage().contains("FRONTIER_BROKER_NON_HUMAN_ORIGIN_DENIED"));
            assertEquals(0, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void typedBrokerFailureIsPropagatedWithoutSecretMaterial() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/frontier/complete", exchange -> {
            byte[] response = "{\"error\":{\"type\":\"provider_billing_exhausted\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            FrontierBrokerLlmProviderClient client = new FrontierBrokerLlmProviderClient(
                    LlmProvider.ANTHROPIC,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "internal-token",
                    HttpClient.newHttpClient(), mapper);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> client.complete(new LlmRequest(
                    LlmProvider.ANTHROPIC, "frontier-model", "system", "input",
                    "REQ-H", "", "semantic-primary", "", FrontierCallBudget.legacyUnbounded(),
                    "HUMAN", "human", "", "", "", "", "")));
            assertEquals("provider_billing_exhausted", failure.getMessage());
        } finally {
            server.stop(0);
        }
    }
}
