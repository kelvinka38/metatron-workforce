package com.metatron.workforce.interaction.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Live self-hosted Ollama transport behind the provider-neutral LLM contract.
 *
 * Root-cause fix (2026-09-16): before this, the planning layer (ExecutionWorkPlanner via
 * InstitutionalIntelligenceRuntime) only ever configured OPENAI/GOOGLE/ANTHROPIC as candidate
 * providers -- entirely separate from the Worker-cognition path (WorkerIntelligenceService ->
 * HttpMetatronCognitionClient -> cognition-node), which already runs Ollama as primary. With
 * OpenAI and Anthropic both out of credit, planning had exactly one real working provider (Google);
 * a single transient Gemini failure (observed live: HTTP 503 "high demand") was enough to fail
 * planning for every Objective with zero redundancy, even though the same host already runs a
 * working self-hosted model. This client lets Ollama serve as a genuine last-resort fallback for
 * planning too, not just for Worker cognition -- it does not replace or reorder the existing paid
 * providers, it adds one more option after them.
 *
 * Calls Ollama's /api/generate directly (not through the cognition-node) because the cognition-node
 * wraps prompts into its own Worker-cognition-specific shape (objective/context/requiredOutput);
 * planning needs its systemContext+userInput passed through largely as-is so the strict JSON plan
 * schema in the prompt is preserved verbatim.
 */
public final class OllamaLlmProviderClient implements LlmProviderClient {
    private static final Duration INTERACTIVE_TIMEOUT = Duration.ofSeconds(110);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OllamaLlmProviderClient(String baseUrl, HttpClient httpClient, ObjectMapper objectMapper) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl must not be blank");
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.OLLAMA;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.provider() != provider()) throw new IllegalArgumentException("wrong provider for Ollama client");
        try {
            String prompt = request.systemContext().isBlank()
                    ? request.userInput()
                    : request.systemContext() + "\n\n" + request.userInput();
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", request.model(),
                    "prompt", prompt,
                    "stream", false));

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/generate"))
                    .timeout(INTERACTIVE_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("ollama_request_failed:status=" + response.statusCode()
                        + ":" + compactBody(response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String text = root.path("response").asText("").trim();
            if (text.isBlank()) throw new IllegalStateException("ollama_response_invalid");

            long promptTokens = token(root, "prompt_eval_count");
            long evalTokens = token(root, "eval_count");
            long total = promptTokens == LlmUsage.UNKNOWN || evalTokens == LlmUsage.UNKNOWN
                    ? LlmUsage.UNKNOWN : promptTokens + evalTokens;
            LlmUsage usage = new LlmUsage(promptTokens, evalTokens, total);
            return new LlmResponse(provider(), request.model(), text, "", usage, Map.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ollama_request_interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("ollama_request_failed", e);
        }
    }

    private static long token(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isIntegralNumber() ? node.asLong() : LlmUsage.UNKNOWN;
    }

    private static String compactBody(String body) {
        if (body == null) return "";
        String trimmed = body.replace('\n', ' ').replace('\r', ' ').trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
    }
}
