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
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<WorkCardKey, EditRecovery> workCardEditRecovery = new ConcurrentHashMap<>();

    public TelegramBotGateway(String botToken, HttpClient httpClient, ObjectMapper objectMapper) {
        this.botToken = Objects.requireNonNull(botToken, "botToken");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
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

    /**
     * Sends one Human-facing Work Card and returns Telegram's message id for future live edits.
     *
     * Work Cards deliberately do not attach ReplyKeyboardMarkup. The persistent Human controls are
     * installed by ordinary channel replies, while the Work Card remains a plain bot text message
     * that Telegram can edit safely for live monitoring.
     */
    public long sendWorkCard(String chatId, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", text);
        ApiResult result = invoke("sendMessage", payload);
        long messageId = result.json().path("result").path("message_id").asLong(-1L);
        if (messageId < 0) throw new IllegalStateException("telegram_work_card_message_id_missing");
        return messageId;
    }

    /**
     * Edits an existing Work Card from canonical Workforce state.
     *
     * Telegram can permanently reject edits for a previously-created message (for example a stale
     * pre-deploy card or a card created with incompatible reply markup). A permanent rejection is
     * recovered once by creating a fresh editable Work Card and transparently routing subsequent
     * refreshes to that replacement. If Telegram also rejects the replacement, editing is disabled
     * for that original monitor key so a 5-second monitor cannot become an unbounded API/log storm;
     * the controller still evaluates terminal state on each tick and will retire the monitor.
     */
    public void editWorkCard(String chatId, long messageId, String text) {
        WorkCardKey key = new WorkCardKey(chatId, messageId);
        EditRecovery recovery = workCardEditRecovery.getOrDefault(key, new EditRecovery(messageId, false));
        if (recovery.disabled()) return;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", chatId);
        payload.put("message_id", recovery.effectiveMessageId());
        payload.put("text", text);
        try {
            invoke("editMessageText", payload);
        } catch (IllegalStateException failure) {
            String failureMessage = failure.getMessage();
            if (failureMessage != null && failureMessage.contains("message is not modified")) return;
            if (!permanentlyUneditableMessage(failureMessage)) throw failure;

            if (recovery.effectiveMessageId() != messageId) {
                workCardEditRecovery.put(key, new EditRecovery(recovery.effectiveMessageId(), true));
                throw new IllegalStateException("telegram_work_card_edit_disabled_after_replacement", failure);
            }

            long replacementMessageId = sendWorkCard(chatId, text);
            workCardEditRecovery.put(key, new EditRecovery(replacementMessageId, false));
        }
    }

    static boolean permanentlyUneditableMessage(String message) {
        if (message == null) return false;
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("message can't be edited")
                || normalized.contains("message to edit not found");
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
                    .uri(URI.create("https://api.telegram.org/bot" + botToken + "/" + method))
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

    private record WorkCardKey(String chatId, long originalMessageId) {}
    private record EditRecovery(long effectiveMessageId, boolean disabled) {}
    private record ApiResult(String raw, JsonNode json) {}
}