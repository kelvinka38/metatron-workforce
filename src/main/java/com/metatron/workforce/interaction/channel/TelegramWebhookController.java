package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** Public Telegram webhook boundary. Enabled only when both Telegram credentials are configured. */
@RestController
@RequestMapping("/telegram")
@ConditionalOnProperty(name = {"telegram.bot-token", "telegram.webhook-secret"})
public final class TelegramWebhookController {
    private final TelegramWebhookAdapter adapter;
    private final TelegramBotGateway gateway;
    private final ObjectMapper objectMapper;

    public TelegramWebhookController(
            @Value("${telegram.webhook-secret:${TELEGRAM_WEBHOOK_SECRET:}}") String secret,
            @Value("${telegram.bot-token:${TELEGRAM_BOT_TOKEN:}}") String botToken,
            ObjectMapper objectMapper) {
        if (secret == null || secret.isBlank()) throw new IllegalStateException("TELEGRAM_WEBHOOK_SECRET_MISSING");
        if (botToken == null || botToken.isBlank()) throw new IllegalStateException("TELEGRAM_BOT_TOKEN_MISSING");
        this.adapter = new TelegramWebhookAdapter(secret);
        this.gateway = new TelegramBotGateway(botToken, java.net.http.HttpClient.newHttpClient(), objectMapper);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(
            @RequestHeader(name = "X-Telegram-Bot-Api-Secret-Token", required = false) String suppliedSecret,
            @RequestBody String body) throws Exception {
        JsonNode update = objectMapper.readTree(body);
        JsonNode message = update.path("message");
        JsonNode chat = message.path("chat");
        String senderId = chat.path("id").asText("");
        String text = message.path("text").asText("");

        ChannelMessage inbound = adapter.receive(suppliedSecret, senderId, text);
        gateway.send(new ChannelMessage("telegram", inbound.senderId(),
                "Workforce received: " + inbound.text()));
        return ResponseEntity.ok().build();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(SecurityException.class)
    ResponseEntity<Void> handleSecurityException() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Void> handleInvalidUpdate() {
        return ResponseEntity.badRequest().build();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Void> handleTelegramUpstreamFailure() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }
}
