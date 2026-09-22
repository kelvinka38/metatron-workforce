package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.ConversationSurfaceModeService;
import com.metatron.workforce.interaction.DirectWorkerConversationService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
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
    static final String CHAT_CONTROL = ConversationSurfaceModeService.CHAT_CONTROL;
    static final String WORK_CONTROL = ConversationSurfaceModeService.WORK_CONTROL;
    static final String MEETING_CONTROL = ConversationSurfaceModeService.MEETING_CONTROL;
    static final String MONITOR_CONTROL = "📊 Monitor";
    static final String WORKERS_CONTROL = DirectWorkerConversationService.WORKERS_CONTROL;
    /**
     * Telegram sendMessage accepts at most 4096 characters. Stay below the hard limit so
     * multi-byte/supplementary Unicode, future presentation changes, and upstream counting
     * differences cannot turn a valid institutional response into a transport dead letter.
     */
    static final int SAFE_MESSAGE_CODE_POINTS = 3800;

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

        List<String> chunks = splitMessageText(message.text());
        ApiResult finalResult = null;
        for (int index = 0; index < chunks.size(); index++) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chat_id", message.senderId());
            payload.put("text", chunks.get(index));
            // The keyboard is persistent. Attach it once, on the final chunk, rather than
            // redundantly to every fragment of one logical institutional response.
            if (index == chunks.size() - 1) payload.put("reply_markup", replyMarkupFor(message.text()));
            finalResult = invoke("sendMessage", payload);
        }
        if (finalResult == null) throw new IllegalStateException("telegram_send_failed:no_message_chunks");
        // Preserve the pre-chunking ChannelGateway contract: callers receive one successful
        // Telegram API response string and only after the complete logical message was delivered.
        return finalResult.raw();
    }

    /**
     * Splits one logical response into bounded Telegram messages without dropping or rewriting
     * institutional content. Chunk boundaries prefer paragraphs/whitespace and never split a
     * supplementary Unicode code point. Concatenating all returned chunks reproduces the input.
     */
    static List<String> splitMessageText(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty()) return List.of(text);

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int remainingCodePoints = text.codePointCount(start, text.length());
            if (remainingCodePoints <= SAFE_MESSAGE_CODE_POINTS) {
                chunks.add(text.substring(start));
                break;
            }

            int hardEnd = text.offsetByCodePoints(start, SAFE_MESSAGE_CODE_POINTS);
            int preferredWindow = SAFE_MESSAGE_CODE_POINTS / 4;
            int preferredStart = text.offsetByCodePoints(start, SAFE_MESSAGE_CODE_POINTS - preferredWindow);
            int end = preferredBoundary(text, preferredStart, hardEnd);
            if (end <= start) end = hardEnd;

            chunks.add(text.substring(start, end));
            start = end;
        }
        return List.copyOf(chunks);
    }

    private static int preferredBoundary(String text, int preferredStart, int hardEnd) {
        for (int i = hardEnd - 1; i >= preferredStart; i--) {
            if (text.charAt(i) == '\n') return i + 1;
        }
        for (int i = hardEnd - 1; i >= preferredStart; i--) {
            if (Character.isWhitespace(text.charAt(i))) return i + 1;
        }
        return hardEnd;
    }

    /** Sends one Human-facing Work Card and returns Telegram's message id for future live edits. */
    public long sendWorkCard(String chatId, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", text);
        // Live Work Cards are edited in place by the monitor. Telegram editMessageText does not
        // permit a message carrying ReplyKeyboardMarkup, so persistent navigation controls belong
        // on separate control replies, never on the live-edit status message itself.
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

    /** Telegram owns presentation only. Product hierarchy is Chat / Work; Meeting is a Work module. */
    static Map<String, Object> depthControlReplyMarkup() { return topLevelReplyMarkup(); }

    static Map<String, Object> topLevelReplyMarkup() {
        return Map.of(
                "keyboard", List.of(
                        List.of(CHAT_CONTROL, WORK_CONTROL),
                        List.of(WORKERS_CONTROL)),
                "resize_keyboard", true,
                "is_persistent", true,
                "input_field_placeholder", "Chat with Metatron or a Worker…");
    }

    static Map<String, Object> workReplyMarkup() {
        return Map.of(
                "keyboard", List.of(
                        List.of(MEETING_CONTROL, MONITOR_CONTROL),
                        List.of(CHAT_CONTROL, WORKERS_CONTROL)),
                "resize_keyboard", true,
                "is_persistent", true,
                "input_field_placeholder", "Work with Metatron…");
    }

    static Map<String, Object> replyMarkupFor(String responseText) {
        String text = responseText == null ? "" : responseText;
        boolean work = text.contains("🧰 WORK ·")
                || text.contains("METATRON MEETING")
                || text.contains("📋 METATRON · WORK ORDER")
                || text.contains("📊 METATRON WORK");
        return work ? workReplyMarkup() : topLevelReplyMarkup();
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
