package com.metatron.workforce.interaction.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Live Anthropic Messages transport behind the provider-neutral LLM contract. */
public final class AnthropicLlmProviderClient implements LlmProviderClient {
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AnthropicLlmProviderClient(String apiKey, HttpClient httpClient, ObjectMapper objectMapper) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (apiKey.isBlank()) throw new IllegalArgumentException("apiKey must not be blank");
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.ANTHROPIC;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.provider() != provider()) throw new IllegalArgumentException("wrong provider for Anthropic client");
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", request.model(),
                    "max_tokens", 2048,
                    "system", request.systemContext(),
                    "messages", List.of(Map.of("role", "user", "content", request.userInput()))
            ));
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("anthropic_request_failed:" + response.statusCode() + ":" + compactError(root));
            }
            String text = root.path("content").path(0).path("text").asText("");
            if (text.isBlank()) throw new IllegalStateException("anthropic_response_invalid");
            return new LlmResponse(provider(), request.model(), text, root.path("id").asText(""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("anthropic_request_interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("anthropic_request_failed", e);
        }
    }

    private static String compactError(JsonNode root) {
        String message = root.path("error").path("message").asText("");
        return message.isBlank() ? "unknown" : message.replace('\n', ' ').replace('\r', ' ');
    }
}
