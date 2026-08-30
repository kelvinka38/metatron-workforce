package com.metatron.workforce.interaction.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Live Google Gemini transport behind the provider-neutral LLM contract. */
public final class GoogleLlmProviderClient implements LlmProviderClient {
    private static final Duration INTERACTIVE_TIMEOUT = Duration.ofSeconds(30);
    private static final long MAX_SINGLE_RETRY_DELAY_MILLIS = 65_000L;
    private static final Pattern RETRY_DELAY_PATTERN = Pattern.compile("(?i)([0-9]+(?:\\.[0-9]+)?)(ms|s)");
    private static final Pattern MESSAGE_RETRY_PATTERN = Pattern.compile("(?i)please\\s+retry\\s+in\\s+([0-9]+(?:\\.[0-9]+)?)(ms|s)");
    private static final List<String> CAPACITY_FALLBACK_MODELS = List.of(
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite");

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

            List<String> candidates = modelCandidates(request.model());
            List<String> failures = new ArrayList<>();
            for (int index = 0; index < candidates.size(); index++) {
                String model = candidates.get(index);
                HttpResponse<String> response = httpClient.send(httpRequest(model, body), HttpResponse.BodyHandlers.ofString());
                JsonNode root = objectMapper.readTree(response.body());
                if (response.statusCode() / 100 == 2) {
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
                    telemetry.put("model_attempts", Integer.toString(index + 1));
                    if (!model.equals(request.model())) {
                        telemetry.put("configured_model", request.model());
                        telemetry.put("capacity_model_fallback", model);
                    }
                    return new LlmResponse(provider(), model, text, root.path("responseId").asText(""), llmUsage, telemetry);
                }

                String error = "model=" + model + ":status=" + response.statusCode() + ":" + compactError(root);
                failures.add(error);
                if (!isCapacityFailure(response.statusCode())) {
                    throw new IllegalStateException("google_request_failed:" + error);
                }
            }
            throw new IllegalStateException("google_capacity_exhausted:" + String.join(" | ", failures));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("google_request_interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("google_request_failed", e);
        }
    }

    private HttpRequest httpRequest(String model, String body) {
        URI uri = URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey);
        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(INTERACTIVE_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    static List<String> modelCandidates(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) throw new IllegalArgumentException("Google model must not be blank");
        Set<String> models = new LinkedHashSet<>();
        models.add(requestedModel.trim());
        models.addAll(CAPACITY_FALLBACK_MODELS);
        return List.copyOf(models);
    }

    static boolean isCapacityFailure(int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    static boolean isRetryableStatus(int statusCode) {
        return isCapacityFailure(statusCode);
    }

    /** Retained for provider-window diagnostics; interactive routing now fails over models before waiting. */
    static long retryDelayMillis(int completedAttempt, JsonNode root, Optional<String> retryAfterHeader) {
        long providerDelay = retryAfterHeader
                .flatMap(GoogleLlmProviderClient::parseRetryAfterHeaderMillis)
                .orElseGet(() -> structuredRetryDelayMillis(root)
                        .orElseGet(() -> messageRetryDelayMillis(root)
                                .orElse(fallbackRetryDelayMillis(completedAttempt))));
        return Math.max(250L, Math.min(MAX_SINGLE_RETRY_DELAY_MILLIS, providerDelay + 250L));
    }

    private static Optional<Long> parseRetryAfterHeaderMillis(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            double seconds = Double.parseDouble(value.trim());
            if (seconds < 0) return Optional.empty();
            return Optional.of((long) Math.ceil(seconds * 1000.0));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Long> structuredRetryDelayMillis(JsonNode root) {
        JsonNode details = root.path("error").path("details");
        if (!details.isArray()) return Optional.empty();
        for (JsonNode detail : details) {
            String type = detail.path("@type").asText("");
            if (!type.endsWith("RetryInfo")) continue;
            Optional<Long> parsed = parseDurationMillis(detail.path("retryDelay").asText(""));
            if (parsed.isPresent()) return parsed;
        }
        return Optional.empty();
    }

    private static Optional<Long> messageRetryDelayMillis(JsonNode root) {
        String message = root.path("error").path("message").asText("");
        Matcher matcher = MESSAGE_RETRY_PATTERN.matcher(message);
        if (!matcher.find()) return Optional.empty();
        return parseDurationMillis(matcher.group(1) + matcher.group(2));
    }

    private static Optional<Long> parseDurationMillis(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        Matcher matcher = RETRY_DELAY_PATTERN.matcher(value.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) return Optional.empty();
        try {
            double amount = Double.parseDouble(matcher.group(1));
            if (amount < 0) return Optional.empty();
            double multiplier = "s".equals(matcher.group(2)) ? 1000.0 : 1.0;
            return Optional.of((long) Math.ceil(amount * multiplier));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static long fallbackRetryDelayMillis(int completedAttempt) {
        return switch (completedAttempt) {
            case 1 -> 300L;
            case 2 -> 750L;
            default -> 1500L;
        };
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
