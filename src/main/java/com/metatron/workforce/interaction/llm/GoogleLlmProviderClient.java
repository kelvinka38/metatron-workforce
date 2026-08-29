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

/** Live Google Gemini transport behind the provider-neutral LLM contract. */
public final class GoogleLlmProviderClient implements LlmProviderClient {
    private static final Duration INTERACTIVE_TIMEOUT = Duration.ofSeconds(30);
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GoogleLlmProviderClient(String apiKey, HttpClient httpClient, ObjectMapper objectMapper) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (apiKey.isBlank()) throw new IllegalArgumentException("apiKey must not be blank");
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.GOOGLE;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.provider() != provider()) throw new IllegalArgumentException("wrong provider for Google client");
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "systemInstruction", Map.of("parts", List.of(Map.of("text", request.systemContext()))),
                    "contents", List.of(Map.of(
                            "role", "user",
                            "parts", List.of(Map.of("text", request.userInput()))
                    ))
            ));
            URI uri = URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                    + request.model() + ":generateContent?key=" + apiKey);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(INTERACTIVE_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("google_request_failed:" + response.statusCode() + ":" + compactError(root));
            }
            String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
            if (text.isBlank()) throw new IllegalStateException("google_response_invalid");
            JsonNode usage = root.path("usageMetadata");
            LlmUsage llmUsage = new LlmUsage(
                    token(usage, "promptTokenCount"),
                    token(usage, "candidatesTokenCount"),
                    token(usage, "totalTokenCount"));
            Map<String, String> telemetry = new LinkedHashMap<>();
            response.headers().firstValue("x-ratelimit-remaining")
                    .filter(value -> !value.isBlank())
                    .ifPresent(value -> telemetry.put("remaining_requests", value));
            return new LlmResponse(provider(), request.model(), text, root.path("responseId").asText(""), llmUsage, telemetry);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("google_request_interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("google_request_failed", e);
        }
    }

    private static long token(JsonNode usage, String field) {
        JsonNode node = usage.path(field);
        return node.isIntegralNumber() ? node.asLong() : LlmUsage.UNKNOWN;
    }

    private static String compactError(JsonNode root) {
        String message = root.path("error").path("message").asText("");
        return message.isBlank() ? "unknown" : message.replace('\n', ' ').replace('\r', ' ');
    }
}
