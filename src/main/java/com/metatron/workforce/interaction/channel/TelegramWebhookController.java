package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.adapter.telegram.ConfiguredTelegramIdentityResolver;
import com.metatron.workforce.adapter.telegram.TelegramIdentityResolver;
import com.metatron.workforce.phase3.ActorRef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** Public Telegram transport boundary. Transport is normalized before entering the canonical Metatron interaction boundary. */
@RestController
@RequestMapping("/telegram")
@ConditionalOnProperty(name = {"telegram.bot-token", "telegram.webhook-secret"})
public final class TelegramWebhookController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TelegramWebhookController.class);

    private final String secret;
    private final TelegramWebhookAdapter adapter;
    private final TelegramBotGateway gateway;
    private final MetatronInteractionOrchestrator orchestrator;
    private final TelegramIdentityResolver identityResolver;
    private final ObjectMapper objectMapper;
    private final TelegramUpdateDeduplicator updateDeduplicator;

    public TelegramWebhookController(
            @Value("${telegram.webhook-secret:${TELEGRAM_WEBHOOK_SECRET:}}") String secret,
            @Value("${telegram.bot-token:${TELEGRAM_BOT_TOKEN:}}") String botToken,
            @Value("${TELEGRAM_ALLOWED_USER_ID:}") String allowedTelegramUserId,
            @Value("${METATRON_ORGANIZATION_ID:}") String organizationContextId,
            @Value("${OPENAI_API_KEY:}") String openAiApiKey,
            @Value("${GEMINI_API_KEY:}") String googleApiKey,
            @Value("${ANTHROPIC_API_KEY:}") String anthropicApiKey,
            @Value("${METATRON_LLM_PROVIDER:AUTO}") String provider,
            @Value("${OPENAI_MODEL:}") String openAiModel,
            @Value("${GEMINI_MODEL:}") String googleModel,
            @Value("${ANTHROPIC_MODEL:}") String anthropicModel,
            @Value("${METATRON_GATEWAY_AUDIT_URL:}") String gatewayAuditUrl,
            @Value("${METATRON_GATEWAY_AUDIT_TOKEN:}") String gatewayAuditToken,
            ObjectMapper objectMapper) {
        if (secret == null || secret.isBlank()) throw new IllegalStateException("TELEGRAM_WEBHOOK_SECRET_MISSING");
        if (botToken == null || botToken.isBlank()) throw new IllegalStateException("TELEGRAM_BOT_TOKEN_MISSING");
        if (allowedTelegramUserId == null || allowedTelegramUserId.isBlank()) throw new IllegalStateException("TELEGRAM_ALLOWED_USER_ID_MISSING");
        if (organizationContextId == null || organizationContextId.isBlank()) throw new IllegalStateException("METATRON_ORGANIZATION_ID_MISSING");

        long configuredTelegramUserId;
        try {
            configuredTelegramUserId = Long.parseLong(allowedTelegramUserId.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalStateException("TELEGRAM_ALLOWED_USER_ID_INVALID", failure);
        }

        this.secret = secret;
        this.adapter = new TelegramWebhookAdapter(secret);
        this.gateway = new TelegramBotGateway(botToken, java.net.http.HttpClient.newHttpClient(), objectMapper);
        this.identityResolver = new ConfiguredTelegramIdentityResolver(
                configuredTelegramUserId,
                new ActorRef("telegram-human", ActorRef.ActorType.HUMAN),
                new ActorRef("metatron-workforce", ActorRef.ActorType.WORKER),
                organizationContextId.trim());

        TelegramIntelligenceResponder intelligence = new TelegramIntelligenceResponder(
                openAiApiKey, googleApiKey, anthropicApiKey, provider,
                openAiModel, googleModel, anthropicModel, objectMapper,
                gatewayAuditUrl, gatewayAuditToken);
        this.orchestrator = new MetatronInteractionOrchestrator(interaction -> {
            String answer = intelligence.respond(
                    interaction.human().actorId(),
                    interaction.text(),
                    interaction.externalMessageReference());
            return new MetatronInteractionOrchestrator.InteractionResponse(
                    interaction.conversationId(),
                    answer,
                    "interaction:" + interaction.externalMessageReference());
        });
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.updateDeduplicator = new TelegramUpdateDeduplicator();
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "channel", "telegram", "webhook", "ready"));
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(
            @RequestHeader(name = "X-Telegram-Bot-Api-Secret-Token", required = false) String suppliedSecret,
            @RequestBody String body) {
        if (!constantTimeEquals(secret, suppliedSecret)) {
            LOG.warn("telegram_webhook_unauthorized");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        long updateId = -1L;
        String chatId = "";
        try {
            JsonNode update;
            try {
                update = objectMapper.readTree(body);
            } catch (Exception parsingFailure) {
                throw new IllegalArgumentException("telegram_payload_invalid", parsingFailure);
            }
            updateId = update.path("update_id").asLong(-1L);
            if (updateId < 0) throw new IllegalArgumentException("telegram_update_id_invalid");

            JsonNode message = update.path("message");
            if (message.isMissingNode() || message.isNull()) {
                LOG.info("telegram_non_message_update_ignored update_id={}", updateId);
                return ResponseEntity.ok().build();
            }

            JsonNode chat = message.path("chat");
            JsonNode from = message.path("from");
            long telegramUserId = from.path("id").asLong(-1L);
            chatId = chat.path("id").asText("");
            String text = message.path("text").asText("");
            if (telegramUserId < 0 || chatId.isBlank() || text.isBlank()) {
                LOG.info("telegram_invalid_message_acknowledged update_id={} telegram_user={} chat={}", updateId, telegramUserId, chatId);
                return ResponseEntity.ok().build();
            }

            TelegramIdentityResolver.Resolution identity = identityResolver.resolve(telegramUserId, parseChatId(chatId));
            ChannelMessage inbound = adapter.receive(suppliedSecret, chatId, text);

            if (!updateDeduplicator.accept(updateId)) {
                LOG.info("telegram_duplicate_update_ignored update_id={} telegram_user={} chat={}", updateId, telegramUserId, chatId);
                return ResponseEntity.ok().build();
            }

            LOG.info("telegram_received update_id={} telegram_user={} chat={} text_length={}", updateId, telegramUserId, chatId, text.length());

            MetatronInteraction interaction = new MetatronInteraction(
                    identity.human(), identity.target(), identity.organizationContextId(),
                    "telegram:" + chatId, "update:" + updateId, inbound.text());

            try {
                MetatronInteractionOrchestrator.InteractionResponse response = orchestrator.handle(interaction);
                String safeAnswer = validateAnswer(inbound.text(), response.text());
                LOG.info("telegram_answer_ready update_id={} telegram_user={} chat={} answer_length={} provenance={}", updateId, telegramUserId, chatId, safeAnswer.length(), response.provenanceReference());
                String delivery = gateway.send(new ChannelMessage("telegram", inbound.senderId(), safeAnswer));
                LOG.info("telegram_send_success update_id={} telegram_user={} chat={} response_bytes={}", updateId, telegramUserId, chatId, delivery.length());
            } catch (RuntimeException failure) {
                LOG.error("telegram_interaction_failed update_id=" + updateId, failure);
                try {
                    String delivery = gateway.send(new ChannelMessage(
                            "telegram", inbound.senderId(),
                            "Metatron could not produce an AI response for this message. The failure has been recorded for recovery."));
                    LOG.info("telegram_failure_notification_sent update_id={} response_bytes={}", updateId, delivery.length());
                } catch (RuntimeException sendFailure) {
                    LOG.error("telegram_failure_notification_failed update_id={}", updateId, sendFailure);
                }
            }
        } catch (RuntimeException failure) {
            LOG.error("telegram_webhook_processing_failed update_id=" + updateId + " chat=" + chatId, failure);
            if (!chatId.isBlank()) {
                try {
                    String delivery = gateway.send(new ChannelMessage(
                            "telegram", chatId,
                            "Metatron nhận được message nhưng gặp lỗi xử lý. Webhook đã được acknowledge và lỗi đã được ghi nhận."));
                    LOG.info("telegram_processing_failure_notification_sent update_id={} response_bytes={}", updateId, delivery.length());
                } catch (RuntimeException sendFailure) {
                    LOG.error("telegram_processing_failure_notification_failed update_id={}", updateId, sendFailure);
                }
            }
        }

        LOG.info("telegram_webhook_ack update_id={} chat={}", updateId, chatId);
        return ResponseEntity.ok().build();
    }

    static String validateAnswer(String inbound, String answer) {
        if (answer == null || answer.isBlank()) throw new IllegalStateException("telegram_answer_empty");
        String normalizedInbound = normalize(inbound);
        String normalizedAnswer = normalize(answer);
        if (normalizedAnswer.equals(normalizedInbound)) throw new IllegalStateException("telegram_response_echo");
        return answer.trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static long parseChatId(String chatId) {
        try {
            return Long.parseLong(chatId);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("telegram_chat_id_invalid", failure);
        }
    }
}
