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
    private final String botToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public TelegramBotGateway(String botToken, HttpClient httpClient, ObjectMapper objectMapper) {
        this.botToken = Objects.requireNonNull(botToken, "botToken");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String channel() {
        return "telegram";
    }

    @Override
    public String send(ChannelMessage message) {
        Objects.requireNonNull(message, "message");
        if (!"telegram".equals(message.channel())) {
            throw new IllegalArgumentException("telegram_message_required");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chat_id", message.senderId());
            payload.put("text", message.text());
            payload.put("reply_markup", depthControlReplyMarkup());
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("telegram_send_failed:http_status=" + response.statusCode());
            }

            JsonNode apiResponse = objectMapper.readTree(response.body());
            if (!apiResponse.path("ok").asBoolean(false)) {
                String description = apiResponse.path("description").asText("unknown_telegram_error");
                int errorCode = apiResponse.path("error_code").asInt(response.statusCode());
                throw new IllegalStateException(
                        "telegram_send_failed:telegram_error=" + errorCode + ":" + description);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("telegram_send_interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("telegram_send_failed:" + e.getClass().getSimpleName() + ":" + safeMessage(e), e);
        }
    }

    /**
     * Telegram renders provider-native controls, but the command strings are the canonical
     * channel-neutral depth-control surface consumed by IntelligenceDepthControlService.
     */
    static Map<String, Object> depthControlReplyMarkup() {
        return Map.of(
                "keyboard", List.of(
                        List.of("/fast", "/analyze", "/deep"),
                        List.of("/auto", "/mode")),
                "resize_keyboard", true,
                "is_persistent", true,
                "input_field_placeholder", "Ask Metatron…");
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "no_message" : message.replaceAll("\\s+", " ").trim();
    }
}
