package com.metatron.workforce.interaction.channel;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded live Work Card refresh/failure/replacement policy for exactly one Objective's Telegram
 * monitor. Extracted out of the Telegram transport controller so this policy -- the exact class of
 * defect behind the 2026-09-22 production incident (Telegram creating a new Work Card roughly every
 * 5 seconds) -- can be exercised directly by tests, not only inspected by reading the scheduling
 * code. This class owns no scheduling of its own; a caller (in production, a
 * {@link java.util.concurrent.ScheduledExecutorService}) invokes {@link #tick()} once per refresh
 * cycle.
 *
 * <p>Root-cause fix: the pre-fix policy replaced the Work Card message on <em>every single</em> edit
 * failure and reset its own failure counter on a successful replacement send, so a deterministically
 * uneditable Work Card (see {@link TelegramBotGateway#sendWorkCard}) produced an unbounded stream of
 * replacement messages -- one roughly every {@code MONITOR_REFRESH_SECONDS}, forever, until the
 * Objective happened to reach a terminal state. This policy instead: (1) treats a successful edit as
 * the exclusively expected normal case (Work Cards are now sent without an incompatible reply
 * markup, so they remain editable); (2) permits at most ONE replacement message for the entire
 * monitor lifetime, regardless of whether that replacement itself later becomes uneditable; (3) bounds
 * any further repeated failure to a fixed number of silent retries before stopping the monitor
 * altogether, rather than ever sending a second replacement.</p>
 */
final class TelegramWorkCardMonitor {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TelegramWorkCardMonitor.class);

    /** Consecutive silent-retry bound (at the 5s production cadence, ~100 seconds) before giving up. */
    static final int MAX_CONSECUTIVE_FAILURES = 20;
    /** At most one replacement Work Card is ever sent for the lifetime of one monitor. */
    static final int MAX_REPLACEMENTS = 1;
    /** Upper bound on one honored Telegram flood-control wait, so a malformed value cannot park a monitor for hours. */
    static final long MAX_RATE_LIMIT_WAIT_SECONDS = 600;
    private static final Pattern RETRY_AFTER = Pattern.compile("(?i)\\b429\\b.*retry after (\\d+)");

    /** The exact two Telegram operations this policy needs; kept minimal for direct test doubles. */
    interface Gateway {
        void editWorkCard(String chatId, long messageId, String text);
        long sendWorkCard(String chatId, String text);
    }

    private final Gateway gateway;
    private final String chatId;
    private final String objectiveId;
    private final Supplier<String> render;
    private final Supplier<Boolean> terminal;
    private final Consumer<String> onStop;
    private final LongSupplier clockMillis;

    private volatile long activeMessageId;
    private long rateLimitedUntilMillis;
    private int consecutiveFailures;
    private int replacementsUsed;
    private volatile boolean stopped;
    private String lastEditedText;

    TelegramWorkCardMonitor(
            Gateway gateway,
            String chatId,
            String objectiveId,
            long initialMessageId,
            Supplier<String> render,
            Supplier<Boolean> terminal,
            Consumer<String> onStop) {
        this(gateway, chatId, objectiveId, initialMessageId, render, terminal, onStop, System::currentTimeMillis);
    }

    TelegramWorkCardMonitor(
            Gateway gateway,
            String chatId,
            String objectiveId,
            long initialMessageId,
            Supplier<String> render,
            Supplier<Boolean> terminal,
            Consumer<String> onStop,
            LongSupplier clockMillis) {
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.chatId = Objects.requireNonNull(chatId, "chatId");
        this.objectiveId = Objects.requireNonNull(objectiveId, "objectiveId");
        this.activeMessageId = initialMessageId;
        this.render = Objects.requireNonNull(render, "render");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.onStop = Objects.requireNonNull(onStop, "onStop");
    }

    long activeMessageId() { return activeMessageId; }

    boolean stopped() { return stopped; }

    /** One refresh cycle. A no-op once this monitor has already stopped itself. */
    void tick() {
        if (stopped) return;
        // Honor Telegram flood control: calling again before "retry after" elapses only extends the
        // ban (production 2026-09-24: two monitors retried every 5 s through 429s, so every Work Card
        // stayed stale for minutes and the one-time replacement was burned on a 429 too).
        if (clockMillis.getAsLong() < rateLimitedUntilMillis) return;
        try {
            String text = render.get();
            // The card only changes with Objective state. Re-sending identical text every 5 s per monitor
            // still costs a Telegram call each time and kept two live cards in flood control about once a
            // minute (production 2026-09-25, 02:37-03:22 "telegram_monitor_rate_limited" every 5 min).
            if (text.equals(lastEditedText)) {
                if (Boolean.TRUE.equals(terminal.get())) stop("refresh-succeeded-terminal");
                return;
            }
            gateway.editWorkCard(chatId, activeMessageId, text);
            lastEditedText = text;
            consecutiveFailures = 0;
            if (Boolean.TRUE.equals(terminal.get())) {
                stop("refresh-succeeded-terminal");
            }
        } catch (RuntimeException failure) {
            onEditFailure(failure);
        }
    }

    private void onEditFailure(RuntimeException failure) {
        long retryAfterSeconds = retryAfterSeconds(failure);
        if (retryAfterSeconds > 0) {
            // Flood control is a transient rate limit, not an uneditable message: never spend the
            // one-time replacement on it and never count it toward giving up.
            rateLimitedUntilMillis = clockMillis.getAsLong() + retryAfterSeconds * 1000L;
            LOG.warn("telegram_monitor_rate_limited objective_id={} chat={} retry_after_seconds={}",
                    objectiveId, chatId, retryAfterSeconds);
            return;
        }
        LOG.warn("telegram_monitor_refresh_failed objective_id={} chat={} message_id={} reason={}",
                objectiveId, chatId, activeMessageId, failure.getMessage());
        // The success path already checks terminal() to decide whether to stop; the failure path
        // must do the same, or a terminal Objective's monitor never stops on its own merely because
        // its last edit happened to fail (e.g. the Objective's own terminal transition raced with a
        // stale message).
        if (Boolean.TRUE.equals(terminal.get())) {
            stop("refresh-failed-but-terminal");
            return;
        }
        // A still-progressing Objective's Work Card message can independently become permanently
        // uneditable (too old, deleted, or edited outside Workforce) while the Objective itself is
        // nowhere near terminal. At most MAX_REPLACEMENTS replacement is ever attempted -- never on
        // every failed edit -- so a deterministic "message can't be edited" failure can never create
        // a replacement message every refresh cycle.
        if (replacementsUsed < MAX_REPLACEMENTS) {
            replacementsUsed++;
            long staleMessageId = activeMessageId;
            try {
                long freshMessageId = gateway.sendWorkCard(chatId, render.get());
                activeMessageId = freshMessageId;
                consecutiveFailures = 0;
                LOG.info("telegram_monitor_message_replaced objective_id={} chat={} old_message_id={} new_message_id={}",
                        objectiveId, chatId, staleMessageId, freshMessageId);
                return;
            } catch (RuntimeException replacementFailure) {
                LOG.warn("telegram_monitor_replacement_send_failed objective_id={} chat={} reason={}",
                        objectiveId, chatId, replacementFailure.getMessage());
            }
        }
        // The one-time replacement is already spent (sent successfully or not) -- bound any further
        // repeated failure to a fixed number of silent retries and then stop, rather than ever
        // sending a second replacement message or retrying forever.
        if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            stop("refresh-failed-consecutive-limit");
        }
    }

    static long retryAfterSeconds(RuntimeException failure) {
        String message = failure == null ? null : failure.getMessage();
        if (message == null) return 0;
        Matcher matcher = RETRY_AFTER.matcher(message);
        if (!matcher.find()) return 0;
        try {
            return Math.min(MAX_RATE_LIMIT_WAIT_SECONDS, Math.max(1, Long.parseLong(matcher.group(1))));
        } catch (NumberFormatException overflow) {
            return MAX_RATE_LIMIT_WAIT_SECONDS;
        }
    }

    private void stop(String reason) {
        if (stopped) return;
        stopped = true;
        onStop.accept(reason);
    }
}
