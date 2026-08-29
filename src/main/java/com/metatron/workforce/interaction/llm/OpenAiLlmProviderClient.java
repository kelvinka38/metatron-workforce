package com.metatron.workforce.interaction.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Live OpenAI transport behind the provider-neutral LLM contract. */
public final class OpenAiLlmProviderClient implements LlmProviderClient {
    private static final Duration INTERACTIVE_TIMEOUT = Duration.ofSeconds(15);
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiLlmProviderClient(String apiKey, HttpClient httpClient, ObjectMapper objectMapper) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (apiKey.isBlank()) throw new IllegalArgumentException("apiKey must not be blank");
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.OPENAI;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.provider() != provider()) throw new IllegalArgumentException("wrong provider for OpenAI client");
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", request.model(),
                    "messages", List.of(
                            Map.of("role", "system", "content", request.systemContext()),
                            Map.of("role", "user", "content", request.userInput())
                    )
            ));
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                    .timeout(INTERACTIVE_TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("openai_request_failed:" + response.statusCode() + ":" + compactError(root));
            }
            String text = root.path("choices").path(0).path("message").path("content").asText("");
            if (text.isBlank()) throw new IllegalStateException("openai_response_invalid");
            JsonNode usage = root.path("usage");
            LlmUsage llmUsage = new LlmUsage(
                    token(usage, "prompt_tokens"),
                    token(usage, "completion_tokens"),
                    token(usage, "total_tokens"));
            Map<String, String> telemetry = new LinkedHashMap<>();
            header(response, "x-ratelimit-remaining-requests").ifPresent(v -> telemetry.put("remaining_requests", v));
            header(response, "x-ratelimit-remaining-tokens").ifPresent(v -> telemetry.put("remaining_tokens", v));
            header(response, "x-ratelimit-reset-requests").ifPresent(v -> telemetry.put("reset_requests", v));
            header(response, "x-ratelimit-reset-tokens").ifPresent(v -> telemetry.put("reset_tokens", v));
            return new LlmResponse(provider(), request.model(), text, root.path("id").asText(""), llmUsage, telemetry);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("openai_request_interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("openai_request_failed", e);
        }
    }

    private static long token(JsonNode usage, String field) {
        JsonNode node = usage.path(field);
        return node.isIntegralNumber() ? node.asLong() : LlmUsage.UNKNOWN;
    }

    private static java.util.Optional<String> header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).filter(value -> !value.isBlank());
    }

    private static String compactError(JsonNode root) {
        String message = root.path("error").path("message").asText("");
        return message.isBlank() ? "unknown" : message.replace('\n', ' ').replace('\r', ' ');
    }
}
