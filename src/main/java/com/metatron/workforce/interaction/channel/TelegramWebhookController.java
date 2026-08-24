package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.adapter.telegram.ConfiguredTelegramIdentityResolver;
import com.metatron.workforce.adapter.telegram.TelegramIdentityResolver;
import com.metatron.workforce.interaction.MetatronInteraction;
import com.metatron.workforce.interaction.MetatronInteractionOrchestrator;
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

/** Public Telegram transport boundary. Transport is normalized before entering the canonical Metatron interaction boundary. */
@RestController
@RequestMapping("/telegram")
@ConditionalOnProperty(name = {"telegram.bot-token", "telegram.webhook-secret"})
public final class TelegramWebhookController {
    private static final Logger LOG = LoggerFactory.getLogger(TelegramWebhookController.class);

    private final TelegramWebhookAdapter adapter;
    private final TelegramBotGateway gateway;
    private final MetatronInteractionOrchestrator orchestrator;
    private final TelegramIdentityResolver identityResolver;
    private final ObjectMapper objectMapper;

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
            ObjectMapper objectMapper) {
        if (secret == null || secret.isBlank()) throw new IllegalStateException("TELEGRAM_WEBHOOK_SECRET_MISSING");
        if (botToken == null || botToken.isBlank()) throw new IllegalStateException("TELEGRAM_BOT_TOKEN_MISSING");
        if (allowedTelegramUserId == null || allowedTelegramUserId.isBlank()) {
            throw new IllegalStateException("TELEGRAM_ALLOWED_USER_ID_MISSING");
        }
        if (organizationContextId == null || organizationContextId.isBlank()) {
            throw new IllegalStateException("METATRON_ORGANIZATION_ID_MISSING");
        }

        long configuredTelegramUserId;
        try {
            configuredTelegramUserId = Long.parseLong(allowedTelegramUserId.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalStateException("TELEGRAM_ALLOWED_USER_ID_INVALID", failure);
        }

        this.adapter = new TelegramWebhookAdapter(secret);
        this.gateway = new TelegramBotGateway(botToken, java.net.http.HttpClient.newHttpClient(), objectMapper);
        this.identityResolver = new ConfiguredTelegramIdentityResolver(
                configuredTelegramUserId,
                new com.metatron.workforce.phase3.ActorRef("telegram-human", com.metatron.workforce.phase3.ActorRef.ActorType.HUMAN),
                new com.metatron.workforce.phase3.ActorRef("metatron-workforce", com.metatron.workforce.phase3.ActorRef.ActorType.WORKER),
                organizationContextId.trim());

        TelegramIntelligenceResponder intelligence = new TelegramIntelligenceResponder(
                openAiApiKey, googleApiKey, anthropicApiKey, provider,
                openAiModel, googleModel, anthropicModel, objectMapper);
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
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(
            @RequestHeader(name = "X-Telegram-Bot-Api-Secret-Token", required = false) String suppliedSecret,
            @RequestBody String body) throws Exception {
        JsonNode update = objectMapper.readTree(body);
        JsonNode message = update.path("message");
        JsonNode chat = message.path("chat");
        JsonNode from = message.path("from");
        long telegramUserId = from.path("id").asLong(-1L);
        String chatId = chat.path("id").asText("");
        String text = message.path("text").asText("");
        long updateId = update.path("update_id").asLong(-1L);

        TelegramIdentityResolver.Resolution identity = identityResolver.resolve(telegramUserId, parseChatId(chatId));
        ChannelMessage inbound = adapter.receive(suppliedSecret, chatId, text);
        LOG.info("telegram_received update_id={} telegram_user={} chat={} text_length={}",
                updateId, telegramUserId, chatId, text.length());

        try {
            MetatronInteraction interaction = new MetatronInteraction(
                    identity.human(),
                    identity.target(),
                    identity.organizationContextId(),
                    "telegram:" + chatId,
                    "update:" + updateId,
                    inbound.text());

            MetatronInteractionOrchestrator.InteractionResponse response = orchestrator.handle(interaction);
            String safeAnswer = validateAnswer(inbound.text(), response.text());
            LOG.info("telegram_answer_ready update_id={} telegram_user={} chat={} answer_length={} provenance={}",
                    updateId, telegramUserId, chatId, safeAnswer.length(), response.provenanceReference());
            gateway.send(new ChannelMessage("telegram", inbound.senderId(), safeAnswer));
        } catch (RuntimeException e) {
            LOG.error("telegram_interaction_failed update_id=" + updateId, e);
            try {
                gateway.send(new ChannelMessage(
                        "telegram", inbound.senderId(),
                        "Metatron could not produce an AI response for this message. The failure has been recorded for recovery."));
            } catch (RuntimeException sendFailure) {
                LOG.error("telegram_failure_notification_failed update_id=" + updateId, sendFailure);
            }
        }

        return ResponseEntity.ok().build();
    }

    static String validateAnswer(String inbound, String answer) {
        if (answer == null || answer.isBlank()) throw new IllegalStateException("telegram_answer_empty");
        String normalizedInbound = normalize(inbound);
        String normalizedAnswer = normalize(answer);
        if (normalizedAnswer.equals(normalizedInbound)) throw new IllegalStateException("telegram_response_echo");
        if (normalizedAnswer.startsWith("workforce received:")) throw new IllegalStateException("telegram_legacy_echo");
        return answer.trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private static long parseChatId(String chatId) {
        try {
            return Long.parseLong(chatId);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("telegram_chat_id_invalid", failure);
        }
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(SecurityException.class)
    ResponseEntity<Void> handleSecurityException() { return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build(); }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Void> handleInvalidUpdate() { return ResponseEntity.badRequest().build(); }
}
