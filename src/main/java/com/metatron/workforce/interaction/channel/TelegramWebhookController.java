package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.adapter.telegram.ConfiguredTelegramIdentityResolver;
import com.metatron.workforce.adapter.telegram.TelegramIdentityResolver;
import com.metatron.workforce.interaction.ChannelInteractionIngressService;
import com.metatron.workforce.interaction.MetatronInteraction;
import com.metatron.workforce.interaction.MetatronInteractionOrchestrator;
import com.metatron.workforce.interaction.intelligence.CanonicalObjectiveControlInterpreter;
import com.metatron.workforce.management.WorkCardRenderer;
import com.metatron.workforce.phase3.ActorRef;
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
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telegram transport adapter. It authenticates Telegram, maps external identifiers to canonical
 * Metatron interaction context, durably records provider RECEIVED/ADMITTED state, submits to the
 * shared channel-neutral ingress, and delivers the resulting response. It owns transport receipt
 * continuity only; canonical Conversation, intelligence, Workforce Objective and execution state
 * remain downstream institutional concerns.
 */
@RestController
@RequestMapping("/telegram")
@ConditionalOnProperty(name = {"telegram.bot-token", "telegram.webhook-secret"})
public final class TelegramWebhookController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TelegramWebhookController.class);
    private static final int INTERACTION_THREADS = 4;
    private static final int INTERACTION_QUEUE = 64;
    private static final int MAX_PROCESSING_ATTEMPTS = 3;
    private static final long MONITOR_REFRESH_SECONDS = 5L;
    private static final Pattern OBJECTIVE_ID = Pattern.compile("(?m)^objective_id=([^\\s]+)$");

    private final String secret;
    private final TelegramWebhookAdapter adapter;
    private final TelegramBotGateway gateway;
    private final ChannelInteractionIngressService interactionIngress;
    private final TelegramIdentityResolver identityResolver;
    private final WorkCardRenderer workCardRenderer;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor interactionExecutor;
    private final ScheduledExecutorService monitorExecutor;
    private final TelegramIngressReceiptStore receiptStore;
    private final boolean channelObjectiveHandoffEnabled;
    private final Set<Long> scheduledUpdates = ConcurrentHashMap.newKeySet();
    private final Map<String, ScheduledFuture<?>> monitorTasks = new ConcurrentHashMap<>();

    public TelegramWebhookController(
            @Value("${telegram.webhook-secret:${TELEGRAM_WEBHOOK_SECRET:}}") String secret,
            @Value("${telegram.bot-token:${TELEGRAM_BOT_TOKEN:}}") String botToken,
            @Value("${TELEGRAM_ALLOWED_USER_ID:}") String allowedTelegramUserId,
            @Value("${METATRON_ORGANIZATION_ID:}") String organizationContextId,
            @Value("${METATRON_TELEGRAM_INGRESS_PATH:/var/lib/metatron-workforce/telegram-ingress-state.json}") String ingressPath,
            @Value("${METATRON_CHANNEL_OBJECTIVE_HANDOFF_ENABLED:false}") boolean channelObjectiveHandoffEnabled,
            ChannelInteractionIngressService interactionIngress,
            WorkCardRenderer workCardRenderer,
            ObjectMapper objectMapper) {
        if (secret == null || secret.isBlank()) throw new IllegalStateException("TELEGRAM_WEBHOOK_SECRET_MISSING");
        if (botToken == null || botToken.isBlank()) throw new IllegalStateException("TELEGRAM_BOT_TOKEN_MISSING");
        if (allowedTelegramUserId == null || allowedTelegramUserId.isBlank()) throw new IllegalStateException("TELEGRAM_ALLOWED_USER_ID_MISSING");
        if (organizationContextId == null || organizationContextId.isBlank()) throw new IllegalStateException("METATRON_ORGANIZATION_ID_MISSING");
        if (ingressPath == null || ingressPath.isBlank()) throw new IllegalStateException("METATRON_TELEGRAM_INGRESS_PATH_MISSING");

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
        this.interactionIngress = Objects.requireNonNull(interactionIngress, "interactionIngress");
        this.workCardRenderer = Objects.requireNonNull(workCardRenderer, "workCardRenderer");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.interactionExecutor = new ThreadPoolExecutor(
                INTERACTION_THREADS, INTERACTION_THREADS, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(INTERACTION_QUEUE), namedDaemonThreads("telegram-interaction-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.interactionExecutor.allowCoreThreadTimeOut(false);
        this.monitorExecutor = Executors.newScheduledThreadPool(2, namedDaemonThreads("telegram-monitor-"));
        this.receiptStore = new TelegramIngressReceiptStore(Path.of(ingressPath.trim()), objectMapper);
        this.channelObjectiveHandoffEnabled = channelObjectiveHandoffEnabled;
        recoverPendingReceipts();
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
                "interaction_queue_depth", interactionExecutor.getQueue().size(),
                "active_task_monitors", monitorTasks.size(),
                "durable_failed", receiptStore.count(TelegramIngressReceiptStore.Status.FAILED),
                "durable_dead_letter", receiptStore.count(TelegramIngressReceiptStore.Status.DEAD_LETTER)));
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

            // Human explicitly enters Task Monitoring Mode. This starts/refreshes one live Work Card.
            if (TelegramBotGateway.MONITOR_CONTROL.equals(text.trim())
                    || "📊 Monitor task".equals(text.trim())
                    || "/monitor".equalsIgnoreCase(text.trim())) {
                String monitorChatId = chatId;
                workCardRenderer.latestObjectiveIdForHuman(identity.human().actorId()).ifPresentOrElse(objectiveId -> {
                    long messageId = gateway.sendWorkCard(monitorChatId, workCardRenderer.render(objectiveId));
                    startLiveMonitor(objectiveId, monitorChatId, messageId);
                }, () -> gateway.send(new ChannelMessage("telegram", monitorChatId,
                        "📊 METATRON WORK\n\nNo autonomous Objective is currently visible for this Human.")));
                LOG.info("telegram_monitor_mode_started update_id={} human={} chat={}", updateId, identity.human().actorId(), chatId);
                return ResponseEntity.ok().build();
            }

            adapter.receive(suppliedSecret, chatId, text);
            TelegramIngressReceiptStore.Receipt receipt = receiptStore.receive(updateId, telegramUserId, chatId, text);
            receipt = receiptStore.admit(updateId);

            // Provider delivery lifetime must never own Objective lifetime. Once the authenticated
            // update is durably RECEIVED/ADMITTED, detach institutional processing from this HTTP
            // request. Objective creation, planning and execution can take arbitrarily longer than
            // Telegram/Cloudflare ingress budgets and are recovered from the durable receipt store.
            scheduleReceipt(receipt.updateId());

            long ackMs = (System.nanoTime() - webhookStarted) / 1_000_000L;
            LOG.info("telegram_webhook_ack update_id={} chat={} ack_ms={} durable_status={} objective_id={} queue_depth={} active_threads={}",
                    updateId, chatId, ackMs, receipt.status(), receipt.objectiveId(), interactionExecutor.getQueue().size(), interactionExecutor.getActiveCount());
            return ResponseEntity.ok().build();
        } catch (SecurityException denied) {
            LOG.warn("telegram_identity_denied update_id={} chat={} reason={}", updateId, chatId, denied.getMessage());
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException malformed) {
            LOG.warn("telegram_invalid_delivery_acknowledged update_id={} chat={} reason={}", updateId, chatId, malformed.getMessage());
            return ResponseEntity.ok().build();
        } catch (RuntimeException failure) {
            LOG.error("telegram_webhook_admission_failed update_id=" + updateId + " chat=" + chatId, failure);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    private void recoverPendingReceipts() {
        for (TelegramIngressReceiptStore.Receipt receipt : receiptStore.recoverable(MAX_PROCESSING_ATTEMPTS)) {
            LOG.warn("telegram_ingress_recovery update_id={} status={} attempts={} objective_id={}",
                    receipt.updateId(), receipt.status(), receipt.attempts(), receipt.objectiveId());
            scheduleReceipt(receipt.updateId());
        }
    }

    private void scheduleReceipt(long updateId) {
        TelegramIngressReceiptStore.Receipt current = receiptStore.find(updateId);
        if (current == null || current.terminal()) return;
        if (!scheduledUpdates.add(updateId)) return;
        try {
            interactionExecutor.execute(() -> processReceipt(updateId));
        } catch (RuntimeException failure) {
            scheduledUpdates.remove(updateId);
            throw failure;
        }
    }

    private void processReceipt(long updateId) {
        boolean retry = false;
        try {
            TelegramIngressReceiptStore.Receipt receipt = receiptStore.claim(updateId, MAX_PROCESSING_ATTEMPTS);
            if (receipt.terminal()) return;

            TelegramIdentityResolver.Resolution identity = identityResolver.resolve(
                    receipt.telegramUserId(), parseChatId(receipt.chatId()));
            ChannelMessage inbound = adapter.receive(secret, receipt.chatId(), receipt.text());
            MetatronInteraction interaction = new MetatronInteraction(
                    identity.human(), identity.target(), identity.organizationContextId(),
                    "conversation:human:" + identity.human().actorId(),
                    "telegram",
                    "telegram:user:" + receipt.telegramUserId(),
                    "telegram:chat:" + receipt.chatId(),
                    receipt.externalMessageReference(),
                    inbound.text());

            long processingStarted = System.nanoTime();
            MetatronInteractionOrchestrator.InteractionResponse response = interactionIngress.handle(interaction);
            String safeAnswer = validateAnswer(inbound.text(), response.text());
            String objectiveId = objectiveIdFromAnswer(safeAnswer);
            if (channelObjectiveHandoffEnabled
                    && requiresObjectiveBeforeAck(receipt.text())
                    && objectiveId.isBlank()) {
                throw new IllegalStateException("explicit_objective_did_not_materialize");
            }
            if (!objectiveId.isBlank()) receiptStore.accepted(updateId, objectiveId);

            long answerMs = (System.nanoTime() - processingStarted) / 1_000_000L;
            LOG.info("telegram_answer_ready update_id={} telegram_user={} chat={} answer_length={} provenance={} attempt={} answer_ms={} objective_id={}",
                    updateId, receipt.telegramUserId(), receipt.chatId(), safeAnswer.length(), response.provenanceReference(),
                    receipt.attempts(), answerMs, objectiveId);

            String delivery;
            if (objectiveId.isBlank()) {
                delivery = gateway.send(new ChannelMessage("telegram", inbound.senderId(), safeAnswer));
            } else {
                long workCardMessageId = gateway.sendWorkCard(inbound.senderId(), workCardRenderer.render(objectiveId));
                startLiveMonitor(objectiveId, inbound.senderId(), workCardMessageId);
                delivery = "work-card:" + workCardMessageId;
            }
            receiptStore.delivered(updateId);
            LOG.info("telegram_send_success update_id={} telegram_user={} chat={} response_bytes={} objective_id={}",
                    updateId, receipt.telegramUserId(), receipt.chatId(), delivery.length(), objectiveId);
        } catch (RuntimeException failure) {
            LOG.error("telegram_interaction_failed update_id=" + updateId, failure);
            TelegramIngressReceiptStore.Receipt failed = receiptStore.failed(
                    updateId, failure, MAX_PROCESSING_ATTEMPTS);
            retry = failed.status() == TelegramIngressReceiptStore.Status.FAILED;
            if (!retry) {
                LOG.error("telegram_interaction_dead_letter update_id={} attempts={} objective_id={}",
                        updateId, failed.attempts(), failed.objectiveId());
                try {
                    gateway.send(new ChannelMessage("telegram", failed.chatId(),
                            "Metatron could not complete this interaction after bounded recovery. The durable failure is retained for reconciliation."));
                } catch (RuntimeException sendFailure) {
                    LOG.error("telegram_dead_letter_notification_failed update_id={}", updateId, sendFailure);
                }
            }
        } finally {
            scheduledUpdates.remove(updateId);
            if (retry) {
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                scheduleReceipt(updateId);
            }
        }
    }

    private void startLiveMonitor(String objectiveId, String chatId, long messageId) {
        ScheduledFuture<?> prior = monitorTasks.remove(objectiveId);
        if (prior != null) prior.cancel(false);
        AtomicLong activeMessageId = new AtomicLong(messageId);
        java.util.concurrent.atomic.AtomicBoolean replacementUsed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<String> lastRendered =
                new java.util.concurrent.atomic.AtomicReference<>(workCardRenderer.render(objectiveId));
        ScheduledFuture<?> future = monitorExecutor.scheduleAtFixedRate(() -> {
            String rendered = workCardRenderer.render(objectiveId);
            boolean quiescent = workCardRenderer.quiescent(objectiveId);
            // Durable state is the source of truth. Do not hit Telegram merely because a 5s timer
            // fired when the Human-visible card has not materially changed.
            if (rendered.equals(lastRendered.get())) {
                if (quiescent) stopMonitor(objectiveId, "unchanged-quiescent");
                return;
            }
            try {
                gateway.editWorkCard(chatId, activeMessageId.get(), rendered);
                lastRendered.set(rendered);
                if (quiescent) stopMonitor(objectiveId, "refresh-succeeded-quiescent");
            } catch (RuntimeException failure) {
                LOG.warn("telegram_monitor_refresh_failed objective_id={} chat={} message_id={} reason={}",
                        objectiveId, chatId, activeMessageId.get(), failure.getMessage());
                // A genuinely stale/deleted/uneditable message gets one replacement only. sendWorkCard
                // creates a live-edit-safe message with no ReplyKeyboardMarkup, so repeating replacement
                // after that point would be a transport storm rather than recovery.
                if (replacementUsed.compareAndSet(false, true)) {
                    long staleMessageId = activeMessageId.get();
                    try {
                        long freshMessageId = gateway.sendWorkCard(chatId, rendered);
                        activeMessageId.set(freshMessageId);
                        lastRendered.set(rendered);
                        LOG.info("telegram_monitor_message_replaced objective_id={} chat={} old_message_id={} new_message_id={}",
                                objectiveId, chatId, staleMessageId, freshMessageId);
                        if (quiescent) stopMonitor(objectiveId, "replacement-succeeded-quiescent");
                        return;
                    } catch (RuntimeException replacementFailure) {
                        LOG.warn("telegram_monitor_replacement_send_failed objective_id={} chat={} reason={}",
                                objectiveId, chatId, replacementFailure.getMessage());
                    }
                }
                stopMonitor(objectiveId, "refresh-failed-after-bounded-replacement");
            }
        }, MONITOR_REFRESH_SECONDS, MONITOR_REFRESH_SECONDS, TimeUnit.SECONDS);
        monitorTasks.put(objectiveId, future);
        LOG.info("telegram_monitor_live objective_id={} chat={} message_id={}", objectiveId, chatId, messageId);
    }

    private void stopMonitor(String objectiveId, String reason) {
        ScheduledFuture<?> completed = monitorTasks.remove(objectiveId);
        if (completed != null) completed.cancel(false);
        LOG.info("telegram_monitor_terminal objective_id={} reason={}", objectiveId, reason);
    }


    static boolean requiresObjectiveBeforeAck(String text) {
        return CanonicalObjectiveControlInterpreter.isExplicitObjectiveControl(text);
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

    static String objectiveIdFromAnswer(String answer) {
        if (answer == null || answer.isBlank()) return "";
        Matcher matcher = OBJECTIVE_ID.matcher(answer);
        return matcher.find() ? matcher.group(1).trim() : "";
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
