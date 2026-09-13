package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpMetatronCognitionClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sendsCanonicalWorkerProvenanceAndParsesMetatronOwnedResponse() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/cognition", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            captured.set(mapper.readTree(exchange.getRequestBody()));
            byte[] response = ("""
                    {"result":"internal result","endpointId":"node-1","modelIdentity":"qualified-model",\
                    "requestReference":"local-ref-1","usage":{"inputTokens":21,"outputTokens":8}}
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            HttpMetatronCognitionClient client = new HttpMetatronCognitionClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "internal-token", mapper);
            IntelligenceOriginContext origin = IntelligenceOriginContext.worker(
                    "WORKER-17", "OBJ-9", "ASG-8", "STEP-7", "ATT-6", "worker.cognition", "REQ-5");
            MetatronCognitionClient.Response response = client.reason(new MetatronCognitionClient.Request(
                    "REQ-5", "worker.cognition", "solve the governed task", "context",
                    List.of("evidence:1"), origin, "structured result"));

            assertEquals("Bearer internal-token", authorization.get());
            assertEquals("WORKER", captured.get().path("provenance").path("originType").asText());
            assertEquals("WORKER-17", captured.get().path("provenance").path("workerId").asText());
            assertEquals("OBJ-9", captured.get().path("provenance").path("objectiveId").asText());
            assertEquals("ASG-8", captured.get().path("provenance").path("assignmentId").asText());
            assertEquals("STEP-7", captured.get().path("provenance").path("stepId").asText());
            assertEquals("ATT-6", captured.get().path("provenance").path("executionAttemptId").asText());
            assertEquals("internal result", response.text());
            assertEquals("node-1", response.endpointId());
            assertEquals("qualified-model", response.modelIdentity());
            assertEquals(21, response.inputTokens());
            assertEquals(8, response.outputTokens());
            assertEquals("local-ref-1", response.requestReference());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonSuccessResponseFailsTruthfullyWithoutFallback() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/cognition", exchange -> {
            byte[] response = "{\"error\":\"capacity\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            HttpMetatronCognitionClient client = new HttpMetatronCognitionClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "internal-token", mapper);
            IntelligenceOriginContext origin = IntelligenceOriginContext.worker(
                    "WORKER-1", "OBJ-1", "ASG-1", "STEP-1", "ATT-1", "worker.cognition", "REQ-1");
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> client.reason(
                    new MetatronCognitionClient.Request("REQ-1", "worker.cognition", "objective", "context",
                            List.of(), origin, "result")));
            assertEquals("metatron_cognition_http_503", failure.getMessage());
        } finally {
            server.stop(0);
        }
    }
}
