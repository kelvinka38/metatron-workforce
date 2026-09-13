package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** HTTP client for the private Metatron-owned Cognition Node contract. */
public final class HttpMetatronCognitionClient implements MetatronCognitionClient {
    private static final HttpClient SHARED_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final URI endpoint;
    private final String authToken;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Duration timeout;

    public HttpMetatronCognitionClient(String baseUrl, String authToken, ObjectMapper mapper) {
        this(baseUrl, authToken, mapper, SHARED_CLIENT, Duration.ofSeconds(120));
    }

    HttpMetatronCognitionClient(String baseUrl, String authToken, ObjectMapper mapper, HttpClient client, Duration timeout) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException("Metatron cognition URL must not be blank");
        if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        this.endpoint = URI.create(normalized + "/v1/cognition");
        this.authToken = authToken == null ? "" : authToken.trim();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public Response reason(Request request) {
        Objects.requireNonNull(request, "request");
        try {
            Map<String, Object> provenance = new LinkedHashMap<>();
            provenance.put("originType", request.origin().originType().name());
            provenance.put("actorId", request.origin().actorId());
            provenance.put("workerId", request.origin().workerId());
            provenance.put("objectiveId", request.origin().objectiveId());
            provenance.put("assignmentId", request.origin().assignmentId());
            provenance.put("stepId", request.origin().stepId());
            provenance.put("executionAttemptId", request.origin().executionAttemptId());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestId", request.requestId());
            body.put("capability", request.capability());
            body.put("objective", request.objective());
            body.put("context", request.context());
            body.put("evidenceReferences", request.evidenceReferences());
            body.put("requiredOutput", request.requiredOutput());
            body.put("provenance", provenance);

            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            if (!authToken.isBlank()) builder.header("Authorization", "Bearer " + authToken);

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("metatron_cognition_http_" + response.statusCode());
            }
            JsonNode root = mapper.readTree(response.body());
            String text = root.path("result").asText("").trim();
            if (text.isBlank()) text = root.path("text").asText("").trim();
            String model = root.path("modelIdentity").asText(root.path("model").asText(""));
            String endpointId = root.path("endpointId").asText("metatron-cognition-node");
            String requestRef = root.path("requestReference").asText(root.path("requestId").asText(request.requestId()));
            long inputTokens = nonNegative(root.path("usage").path("inputTokens").asLong(0));
            long outputTokens = nonNegative(root.path("usage").path("outputTokens").asLong(0));
            return new Response(text, endpointId, model, inputTokens, outputTokens, requestRef);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("metatron cognition interrupted", interrupted);
        } catch (RuntimeException runtime) {
            throw runtime;
        } catch (Exception failure) {
            throw new IllegalStateException("metatron cognition request failed", failure);
        }
    }

    private static long nonNegative(long value) { return Math.max(0L, value); }
}
