package com.metatron.workforce.interaction.channel;

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

/** Outbound Telegram Bot API gateway. */
public final class TelegramBotGateway implements ChannelGateway {
    static final String FAST_CONTROL = "⚡ Fast";
    static final String ANALYZE_CONTROL = "🧠 Analyze";
    static final String DEEP_CONTROL = "🔬 Deep";
    static final String AUTO_CONTROL = "🤖 Auto";
    static final String MODE_CONTROL = "🎛 Mode";
    static final String MONITOR_CONTROL = "📊 Monitor task";

    private final String botToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;

    public TelegramBotGateway(String botToken, HttpClient httpClient, ObjectMapper objectMapper) {
        this(botToken, httpClient, objectMapper,
                System.getenv().getOrDefault("TELEGRAM_API_BASE_URL", "https://api.telegram.org"));
    }

    TelegramBotGateway(String botToken, HttpClient httpClient, ObjectMapper objectMapper, String apiBaseUrl) {
        this.botToken = Objects.requireNonNull(botToken, "botToken");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        String normalized = Objects.requireNonNull(apiBaseUrl, "apiBaseUrl").trim();
        if (normalized.isBlank()) throw new IllegalArgumentException("telegram_api_base_url_required");
        this.apiBaseUrl = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    @Override
    public String channel() { return "telegram"; }

    @Override
    public String send(ChannelMessage message) {
        Objects.requireNonNull(message, "message");
        if (!"telegram".equals(message.channel())) throw new IllegalArgumentException("telegram_message_required");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", message.senderId());
        payload.put("text", message.text());
        payload.put("reply_markup", depthControlReplyMarkup());
        return invoke("sendMessage", payload).raw();
    }

    /** Sends one Human-facing Work Card and returns Telegram's message id for future live edits. */
    public long sendWorkCard(String chatId, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", text);
        payload.put("reply_markup", depthControlReplyMarkup());
        ApiResult result = invoke("sendMessage", payload);
        long messageId = result.json().path("result").path("message_id").asLong(-1L);
        if (messageId < 0) throw new IllegalStateException("telegram_work_card_message_id_missing");
        return messageId;
    }

    /** Edits an existing Work Card; canonical state is supplied by Workforce, never by Telegram. */
    public void editWorkCard(String chatId, long messageId, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", chatId);
        payload.put("message_id", messageId);
        payload.put("text", text);
        try {
            invoke("editMessageText", payload);
        } catch (IllegalStateException failure) {
            if (failure.getMessage() != null && failure.getMessage().contains("message is not modified")) return;
            throw failure;
        }
    }

    /** Telegram owns presentation only; canonical depth/task state remains channel-neutral. */
    static Map<String, Object> depthControlReplyMarkup() {
        return Map.of(
                "keyboard", List.of(
                        List.of(FAST_CONTROL, ANALYZE_CONTROL, DEEP_CONTROL),
                        List.of(AUTO_CONTROL, MODE_CONTROL),
                        List.of(MONITOR_CONTROL)),
                "resize_keyboard", true,
                "is_persistent", true,
                "input_field_placeholder", "Ask Metatron…");
    }

    private ApiResult invoke(String method, Map<String, Object> payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/bot" + botToken + "/" + method))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode apiResponse = objectMapper.readTree(response.body());
            if (response.statusCode() / 100 != 2 || !apiResponse.path("ok").asBoolean(false)) {
                String description = apiResponse.path("description").asText("unknown_telegram_error");
                int errorCode = apiResponse.path("error_code").asInt(response.statusCode());
                throw new IllegalStateException("telegram_send_failed:telegram_error=" + errorCode + ":" + description);
            }
            return new ApiResult(response.body(), apiResponse);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("telegram_send_interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("telegram_send_failed:" + e.getClass().getSimpleName() + ":" + safeMessage(e), e);
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "no_message" : message.replaceAll("\\s+", " ").trim();
    }

    private record ApiResult(String raw, JsonNode json) {}
}
