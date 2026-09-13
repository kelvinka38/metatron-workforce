package com.metatron.workforce.interaction.llm;

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

/** Internal client for the credential-owning Frontier Broker. */
public final class FrontierBrokerLlmProviderClient implements LlmProviderClient {
    private final LlmProvider provider;
    private final URI endpoint;
    private final String authToken;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public FrontierBrokerLlmProviderClient(
            LlmProvider provider,
            String brokerBaseUrl,
            String authToken,
            HttpClient httpClient,
            ObjectMapper mapper) {
        this.provider = Objects.requireNonNull(provider, "provider");
        String base = brokerBaseUrl == null ? "" : brokerBaseUrl.trim();
        if (base.isBlank()) throw new IllegalArgumentException("frontier broker URL must not be blank");
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.endpoint = URI.create(base + "/v1/frontier/complete");
        this.authToken = authToken == null ? "" : authToken.trim();
        if (this.authToken.isBlank()) throw new IllegalArgumentException("frontier broker auth must not be blank");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public LlmProvider provider() {
        return provider;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.provider() != provider) throw new IllegalArgumentException("frontier broker provider mismatch");
        if (!"HUMAN".equals(request.originType())) {
            throw new SecurityException("FRONTIER_BROKER_NON_HUMAN_ORIGIN_DENIED origin=" + request.originType());
        }
        try {
            Map<String, Object> provenance = new LinkedHashMap<>();
            provenance.put("originType", request.originType());
            provenance.put("actorId", request.actorId());
            provenance.put("workerId", request.workerId());
            provenance.put("objectiveId", request.objectiveId());
            provenance.put("assignmentId", request.assignmentId());
            provenance.put("stepId", request.stepId());
            provenance.put("executionAttemptId", request.executionAttemptId());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("provider", provider.name());
            body.put("model", request.model());
            body.put("systemContext", request.systemContext());
            body.put("userInput", request.userInput());
            body.put("logicalRequestRef", request.logicalRequestRef());
            body.put("caseRef", request.caseRef());
            body.put("purpose", request.purpose());
            body.put("reasonCode", request.reasonCode());
            body.put("provenance", provenance);

            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + authToken)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = response.body() == null || response.body().isBlank()
                    ? mapper.createObjectNode() : mapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String type = root.path("error").path("type").asText("");
                if (type.isBlank()) type = root.path("error").asText("frontier_broker_http_" + response.statusCode());
                throw new IllegalStateException(type);
            }
            String text = root.path("text").asText("").trim();
            if (text.isBlank()) throw new IllegalStateException("frontier_broker_empty_response");
            String model = root.path("model").asText(request.model());
            String requestReference = root.path("requestReference").asText("");
            JsonNode usage = root.path("usage");
            long input = usage.path("inputTokens").asLong(LlmUsage.UNKNOWN);
            long output = usage.path("outputTokens").asLong(LlmUsage.UNKNOWN);
            long total = usage.path("totalTokens").asLong(
                    input >= 0 && output >= 0 ? input + output : LlmUsage.UNKNOWN);
            Map<String, String> telemetry = new LinkedHashMap<>();
            telemetry.put("transport", "frontier-broker");
            telemetry.put("originType", request.originType());
            telemetry.put("brokerEndpoint", endpoint.toString());
            return new LlmResponse(provider, model, text, requestReference,
                    new LlmUsage(input, output, total), Map.copyOf(telemetry));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("frontier_broker_interrupted", interrupted);
        } catch (RuntimeException runtime) {
            throw runtime;
        } catch (Exception failure) {
            throw new IllegalStateException("frontier_broker_request_failed", failure);
        }
    }
}
