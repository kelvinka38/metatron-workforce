package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/** Public Telegram webhook boundary. Valid updates are acknowledged exactly once to prevent Telegram retries. */
@RestController
@RequestMapping("/telegram")
@ConditionalOnProperty(name = {"telegram.bot-token", "telegram.webhook-secret"})
public final class TelegramWebhookController {
    private static final Logger LOG = LoggerFactory.getLogger(TelegramWebhookController.class);

    private final TelegramWebhookAdapter adapter;
    private final TelegramBotGateway gateway;
    private final TelegramIntelligenceResponder intelligence;
    private final ObjectMapper objectMapper;

    public TelegramWebhookController(
            @Value("${telegram.webhook-secret:${TELEGRAM_WEBHOOK_SECRET:}}") String secret,
            @Value("${telegram.bot-token:${TELEGRAM_BOT_TOKEN:}}") String botToken,
            @Value("${OPENAI_API_KEY:}") String openAiApiKey,
            @Value("${GEMINI_API_KEY:}") String googleApiKey,
            @Value("${ANTHROPIC_API_KEY:}") String anthropicApiKey,
            @Value("${METATRON_LLM_PROVIDER:AUTO}") String provider,
            @Value("${OPENAI_MODEL:}") String openAiModel,
            @Value("${GEMINI_MODEL:}") String googleModel,
            @Value("${ANTHROPIC_MODEL:}") String anthropicModel,
            ObjectMapper objectMapper) {
        if (secret == null || secret.isBlank()) throw new IllegalStateException("TELEGRAM_WEBHOOK_SECRET_MISSING");
        if (botToken == null || botToken.isBlank()) throw new IllegalStateException("TELEGRAM_BOT_TOKEN_MISSING");
        this.adapter = new TelegramWebhookAdapter(secret);
        this.gateway = new TelegramBotGateway(botToken, java.net.http.HttpClient.newHttpClient(), objectMapper);
        this.intelligence = new TelegramIntelligenceResponder(
                openAiApiKey, googleApiKey, anthropicApiKey, provider,
                openAiModel, googleModel, anthropicModel, objectMapper);
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
        long updateId = update.path("update_id").asLong(-1L);

        ChannelMessage inbound = adapter.receive(suppliedSecret, senderId, text);
        LOG.info("telegram_received update_id={} sender={} text_length={}", updateId, senderId, text.length());

        try {
            String answer = intelligence.respond(inbound.senderId(), inbound.text());
            String safeAnswer = validateAnswer(inbound.text(), answer);
            LOG.info("telegram_answer_ready update_id={} sender={} answer_length={}", updateId, senderId, safeAnswer.length());
            gateway.send(new ChannelMessage("telegram", inbound.senderId(), safeAnswer));
        } catch (RuntimeException e) {
            LOG.error("telegram_intelligence_failed update_id=" + updateId, e);
            try {
                gateway.send(new ChannelMessage(
                        "telegram", inbound.senderId(),
                        "Metatron could not produce an AI response for this message. The failure has been recorded for recovery."));
            } catch (RuntimeException sendFailure) {
                LOG.error("telegram_failure_notification_failed update_id=" + updateId, sendFailure);
            }
        }

        // Telegram must receive 2xx for a valid update; otherwise it retries the same update.
        return ResponseEntity.ok().build();
    }

    static String validateAnswer(String inbound, String answer) {
        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException("telegram_answer_empty");
        }
        String normalizedInbound = normalize(inbound);
        String normalizedAnswer = normalize(answer);
        if (normalizedAnswer.equals(normalizedInbound)) {
            throw new IllegalStateException("telegram_response_echo");
        }
        if (normalizedAnswer.startsWith("workforce received:")) {
            throw new IllegalStateException("telegram_legacy_echo");
        }
        return answer.trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(SecurityException.class)
    ResponseEntity<Void> handleSecurityException() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Void> handleInvalidUpdate() {
        return ResponseEntity.badRequest().build();
    }
}
