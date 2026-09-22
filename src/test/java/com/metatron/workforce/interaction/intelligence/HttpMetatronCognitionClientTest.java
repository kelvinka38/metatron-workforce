package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpMetatronCognitionClientTest {
    @Test
    void preservesStructuredNodeFailureCodeWithoutLeakingResponseBody() throws Exception {
        HttpServer server = server(502, "{\"error\":\"all_providers_failed\",\"detail\":\"secret-provider-body\"}");
        try {
            HttpMetatronCognitionClient client = client(server);
            HttpMetatronCognitionClient.MetatronCognitionHttpException failure = assertThrows(
                    HttpMetatronCognitionClient.MetatronCognitionHttpException.class,
                    () -> client.reason(request()));

            assertEquals(502, failure.statusCode());
            assertEquals("all_providers_failed", failure.errorCode());
            assertEquals("metatron_cognition_http_502:all_providers_failed", failure.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesBoundedProviderAttemptObjectsFromNodeSuccess() throws Exception {
        String body = """
                {"result":"ok","modelIdentity":"qwen3:8b","endpointId":"metatron-cognition-node:test:ollama",
                 "requestReference":"REQ-1","usage":{"inputTokens":10,"outputTokens":2},
                 "providerUsed":"ollama","latencyMs":42,"fallbackOccurred":false,
                 "providerAttempts":[{"provider":"gemini","durationMs":12,"failureClass":"timeout"}]}
                """;
        HttpServer server = server(200, body);
        try {
            MetatronCognitionClient.Response response = client(server).reason(request());
            assertEquals(List.of("gemini=timeout@12ms"), response.providerAttempts());
            assertEquals("ollama", response.provider());
            assertEquals(42L, response.latencyMillis());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requestAwareOutputBudgetReachesTheCognitionNodeAsMaxOutputTokens() throws Exception {
        // Root-cause fix: the cognition-node previously received no per-request output-token hint at
        // all and applied one global 256-token ceiling to every request. The HTTP transport must now
        // forward the caller's CognitiveOutputBudget so a work-product-generation request (e.g. a fresh
        // workspace.file.write) is not truncated by that old, too-small ceiling.
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        HttpServer server = capturingServer(200,
                "{\"result\":\"ok\",\"modelIdentity\":\"test\",\"endpointId\":\"metatron-cognition-node:test:ollama\","
                        + "\"requestReference\":\"REQ-2\",\"usage\":{\"inputTokens\":1,\"outputTokens\":1}}",
                capturedBody);
        try {
            HttpMetatronCognitionClient client = client(server);
            client.reason(new MetatronCognitionClient.Request(
                    "REQ-2",
                    "worker.cognition",
                    "write a fresh application file",
                    "context",
                    List.of("evidence:test"),
                    IntelligenceOriginContext.worker(
                            "WORKER-TEST", "OBJ-1", "ASG-1", "STEP-1", "ATT-1", "worker.cognition", "REQ-2"),
                    "strict json",
                    CognitiveOutputBudget.CONTENT_GENERATION));

            assertEquals(CognitiveOutputBudget.CONTENT_GENERATION.maxOutputTokens(),
                    capturedBody.get().path("maxOutputTokens").asInt());
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer capturingServer(int status, String body, AtomicReference<JsonNode> capturedBody)
            throws Exception {
        ObjectMapper json = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/cognition", exchange -> {
            capturedBody.set(json.readTree(exchange.getRequestBody()));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static HttpMetatronCognitionClient client(HttpServer server) {
        return new HttpMetatronCognitionClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-token",
                new ObjectMapper(),
                Duration.ofSeconds(2));
    }

    private static HttpServer server(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/cognition", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static MetatronCognitionClient.Request request() {
        return new MetatronCognitionClient.Request(
                "REQ-1",
                "worker.cognition",
                "choose action",
                "context",
                List.of("evidence:test"),
                IntelligenceOriginContext.worker(
                        "WORKER-TEST", "OBJ-1", "ASG-1", "STEP-1", "ATT-1", "worker.cognition", "REQ-1"),
                "strict json");
    }
}
