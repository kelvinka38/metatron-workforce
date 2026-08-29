package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.adapter.telegram.ConfiguredTelegramIdentityResolver;
import com.metatron.workforce.adapter.telegram.TelegramIdentityResolver;
import com.metatron.workforce.interaction.MetatronConversationRuntime;
import com.metatron.workforce.interaction.MetatronInteraction;
import com.metatron.workforce.interaction.MetatronInteractionOrchestrator;
import com.metatron.workforce.interaction.intelligence.MetatronIntelligenceResponder;
import com.metatron.workforce.interaction.memory.PersistentConversationMemoryStore;
import com.metatron.workforce.phase3.ActorRef;
import com.metatron.workforce.workers.audit.RepositoryAuditExecutionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Telegram transport adapter. It only authenticates, normalizes and delivers messages.
 * Conversation memory and intelligence live in the channel-neutral Metatron runtime.
 */
@RestController
@RequestMapping("/telegram")
@ConditionalOnProperty(name = {"telegram.bot-token", "telegram.webhook-secret"})
public final class TelegramWebhookController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TelegramWebhookController.class);
    private static final int INTERACTION_THREADS = 4;
    private static final int INTERACTION_QUEUE = 64;
    private static final int MEMORY_MAX_TURNS = 32;
    private static final int MEMORY_MAX_CHARS = 32000;

    private final String secret;
    private final TelegramWebhookAdapter adapter;
    private final TelegramBotGateway gateway;
    private final MetatronInteractionOrchestrator orchestrator;
    private final TelegramIdentityResolver identityResolver;
    private final TelegramInstitutionalWorkDispatcher workDispatcher;
    private final ObjectMapper objectMapper;
    private final TelegramUpdateDeduplicator updateDeduplicator;
    private final ThreadPoolExecutor interactionExecutor;

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
            RepositoryAuditExecutionService repositoryAuditExecutionService,
            @Value("${METATRON_CONVERSATION_MEMORY_PATH:${METATRON_TELEGRAM_MEMORY_PATH:/var/lib/metatron-workforce/telegram-conversations}}") String conversationMemoryPath,
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
        java.net.http.HttpClient telegramHttpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(java.net.http.HttpClient.Version.HTTP_2)
                .build();
        this.gateway = new TelegramBotGateway(botToken, telegramHttpClient, objectMapper);
        this.identityResolver = new ConfiguredTelegramIdentityResolver(
                configuredTelegramUserId,
                new ActorRef("human-primary", ActorRef.ActorType.HUMAN),
                new ActorRef("metatron-workforce", ActorRef.ActorType.WORKER),
                organizationContextId.trim());
        this.workDispatcher = new TelegramInstitutionalWorkDispatcher(repositoryAuditExecutionService);

        MetatronIntelligenceResponder intelligence = new MetatronIntelligenceResponder(
                openAiApiKey, googleApiKey, anthropicApiKey, provider,
                openAiModel, googleModel, anthropicModel, objectMapper,
                gatewayAuditUrl, gatewayAuditToken);
        MetatronConversationRuntime conversationRuntime = new MetatronConversationRuntime(
                new PersistentConversationMemoryStore(Path.of(conversationMemoryPath), objectMapper),
                intelligence,
                MEMORY_MAX_TURNS,
                MEMORY_MAX_CHARS);

        this.orchestrator = new MetatronInteractionOrchestrator(
                interaction -> conversationRuntime.handle(interaction, "telegram"));
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.updateDeduplicator = new TelegramUpdateDeduplicator();
        this.interactionExecutor = new ThreadPoolExecutor(
                INTERACTION_THREADS, INTERACTION_THREADS, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(INTERACTION_QUEUE), namedDaemonThreads("telegram-interaction-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.interactionExecutor.allowCoreThreadTimeOut(false);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "channel", "telegram",
                "role", "transport-adapter",
                "webhook", "ready",
                "interaction_threads", INTERACTION_THREADS,
                "interaction_queue_capacity", INTERACTION_QUEUE,
                "interaction_queue_depth", interactionExecutor.getQueue().size()));
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(
            @RequestHeader(name = "X-Telegram-Bot-Api-Secret-Token", required = false) String suppliedSecret,
            @RequestBody String body) {
        long webhookStarted = System.nanoTime();
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
                    "conversation:human:" + identity.human().actorId(),
                    "telegram:update:" + updateId,
                    inbound.text());

            final long acceptedUpdateId = updateId;
            final String acceptedChatId = chatId;
            final long acceptedTelegramUserId = telegramUserId;
            final ChannelMessage acceptedInbound = inbound;
            final long acceptedAt = System.nanoTime();
            interactionExecutor.execute(() -> processInteraction(
                    acceptedUpdateId, acceptedTelegramUserId, acceptedChatId, acceptedInbound, interaction, acceptedAt));

            long ackMs = (System.nanoTime() - webhookStarted) / 1_000_000L;
            LOG.info("telegram_webhook_ack update_id={} chat={} ack_ms={} queue_depth={} active_threads={}",
                    updateId, chatId, ackMs, interactionExecutor.getQueue().size(), interactionExecutor.getActiveCount());
            return ResponseEntity.ok().build();
        } catch (RuntimeException failure) {
            LOG.error("telegram_webhook_processing_failed update_id=" + updateId + " chat=" + chatId, failure);
            if (!chatId.isBlank()) {
                try {
                    gateway.send(new ChannelMessage("telegram", chatId,
                            "Metatron nhận được message nhưng gặp lỗi xử lý. Webhook đã được acknowledge và lỗi đã được ghi nhận."));
                } catch (RuntimeException sendFailure) {
                    LOG.error("telegram_processing_failure_notification_failed update_id={}", updateId, sendFailure);
                }
            }
            return ResponseEntity.ok().build();
        }
    }

    private void processInteraction(long updateId, long telegramUserId, String chatId,
                                    ChannelMessage inbound, MetatronInteraction interaction, long acceptedAt) {
        long processingStarted = System.nanoTime();
        long queueMs = (processingStarted - acceptedAt) / 1_000_000L;
        try {
            Optional<String> institutional = workDispatcher.dispatch(interaction);
            String safeAnswer;
            String provenance;
            if (institutional.isPresent()) {
                safeAnswer = institutional.get();
                provenance = "institutional-work:" + interaction.externalMessageReference();
                LOG.info("telegram_institutional_work_terminal update_id={} telegram_user={} chat={} result={}",
                        updateId, telegramUserId, chatId, safeAnswer);
            } else {
                MetatronInteractionOrchestrator.InteractionResponse response = orchestrator.handle(interaction);
                safeAnswer = validateAnswer(inbound.text(), response.text());
                provenance = response.provenanceReference();
            }
            long answerMs = (System.nanoTime() - processingStarted) / 1_000_000L;
            LOG.info("telegram_answer_ready update_id={} telegram_user={} chat={} answer_length={} provenance={} queue_ms={} answer_ms={}",
                    updateId, telegramUserId, chatId, safeAnswer.length(), provenance, queueMs, answerMs);
            long sendStarted = System.nanoTime();
            String delivery = gateway.send(new ChannelMessage("telegram", inbound.senderId(), safeAnswer));
            long sendMs = (System.nanoTime() - sendStarted) / 1_000_000L;
            long totalMs = (System.nanoTime() - acceptedAt) / 1_000_000L;
            LOG.info("telegram_send_success update_id={} telegram_user={} chat={} response_bytes={} queue_ms={} answer_ms={} send_ms={} total_ms={}",
                    updateId, telegramUserId, chatId, delivery.length(), queueMs, answerMs, sendMs, totalMs);
        } catch (RuntimeException failure) {
            LOG.error("telegram_interaction_failed update_id=" + updateId, failure);
            try {
                String delivery = gateway.send(new ChannelMessage(
                        "telegram", inbound.senderId(),
                        "Metatron could not complete this interaction. The failure has been recorded for recovery."));
                LOG.info("telegram_failure_notification_sent update_id={} response_bytes={}", updateId, delivery.length());
            } catch (RuntimeException sendFailure) {
                LOG.error("telegram_failure_notification_failed update_id={}", updateId, sendFailure);
            }
        }
    }

    static String validateAnswer(String inbound, String answer) {
        if (answer == null || answer.isBlank()) throw new IllegalStateException("telegram_answer_empty");
        String normalizedInbound = normalize(inbound);
        String normalizedAnswer = normalize(answer);
        if (normalizedAnswer.equals(normalizedInbound)) throw new IllegalStateException("telegram_response_echo");
        String legacyPrefix = "workforce received: ";
        if (normalizedAnswer.startsWith(legacyPrefix)
                && normalize(answer.substring(legacyPrefix.length())).equals(normalizedInbound)) {
            throw new IllegalStateException("telegram_legacy_workforce_echo");
        }
        return answer.trim();
    }

    private static ThreadFactory namedDaemonThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
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
