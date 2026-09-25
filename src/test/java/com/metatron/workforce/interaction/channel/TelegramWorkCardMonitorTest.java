package com.metatron.workforce.interaction.channel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance coverage for the 2026-09-22 production incident: Telegram created a new Work Card
 * roughly every 5 seconds because the pre-fix policy (a) replaced the message on every single edit
 * failure and (b) reset its own failure counter whenever that replacement send succeeded, so a
 * deterministically uneditable Work Card produced an unbounded stream of replacement messages.
 * {@link TelegramWorkCardMonitor} is the extracted, directly testable policy; these tests exercise
 * it without any real Telegram transport.
 */
final class TelegramWorkCardMonitorTest {

    @Test
    void successfulRefreshEditsTheSameMessageAndNeverSendsANewOne() {
        RecordingGateway gateway = new RecordingGateway();
        List<String> stoppedReasons = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger version = new java.util.concurrent.atomic.AtomicInteger();
        TelegramWorkCardMonitor monitor = new TelegramWorkCardMonitor(
                gateway, "chat-1", "objective-1", 1000L,
                () -> "card text v" + version.incrementAndGet(), () -> false, stoppedReasons::add);

        monitor.tick();
        monitor.tick();
        monitor.tick();

        assertEquals(3, gateway.editCalls.size());
        assertTrue(gateway.editCalls.stream().allMatch(call -> call.messageId() == 1000L),
                "every refresh must edit the same message id");
        assertEquals(0, gateway.sendCalls.size(), "a successful normal refresh must never call sendMessage");
        assertTrue(stoppedReasons.isEmpty());
        assertFalse(monitor.stopped());
        assertEquals(1000L, monitor.activeMessageId());
    }

    @Test
    void terminalObjectiveStopsTheMonitorAfterASuccessfulRefresh() {
        RecordingGateway gateway = new RecordingGateway();
        AtomicBoolean terminal = new AtomicBoolean(false);
        List<String> stoppedReasons = new ArrayList<>();
        TelegramWorkCardMonitor monitor = new TelegramWorkCardMonitor(
                gateway, "chat-1", "objective-1", 1000L,
                () -> terminal.get() ? "card text: completed" : "card text: executing", terminal::get, stoppedReasons::add);

        monitor.tick();
        assertFalse(monitor.stopped());

        terminal.set(true);
        monitor.tick();

        assertTrue(monitor.stopped());
        assertEquals(List.of("refresh-succeeded-terminal"), stoppedReasons);

        monitor.tick();
        assertEquals(2, gateway.editCalls.size(), "tick() must be a no-op once the monitor has already stopped");
    }

    @Test
    void terminalObjectiveStopsTheMonitorEvenWhenTheLastRefreshFails() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.editFailure = telegramCantBeEditedFailure();
        List<String> stoppedReasons = new ArrayList<>();
        TelegramWorkCardMonitor monitor = new TelegramWorkCardMonitor(
                gateway, "chat-1", "objective-1", 1000L,
                () -> "card text", () -> true, stoppedReasons::add);

        monitor.tick();

        assertTrue(monitor.stopped());
        assertEquals(List.of("refresh-failed-but-terminal"), stoppedReasons);
        assertEquals(0, gateway.sendCalls.size(), "a terminal Objective must never trigger a replacement send");
    }

    @Test
    void unchangedContentIsNotReEditedAndNeverCreatesAReplacement() {
        // Production 2026-09-25: every monitor re-sent identical card text every 5 s, and two live cards
        // hit Telegram flood control roughly once a minute. Unchanged content now costs no Telegram call.
        RecordingGateway gateway = new RecordingGateway();
        List<String> stoppedReasons = new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<String> card =
                new java.util.concurrent.atomic.AtomicReference<>("identical card text");
        TelegramWorkCardMonitor monitor = new TelegramWorkCardMonitor(
                gateway, "chat-1", "objective-1", 1000L,
                card::get, () -> false, stoppedReasons::add);

        for (int i = 0; i < 5; i++) monitor.tick();
        assertEquals(1, gateway.editCalls.size(), "identical text is edited once, then skipped");

        card.set("changed card text");
        monitor.tick();
        monitor.tick();
        assertEquals(2, gateway.editCalls.size(), "a state change is edited exactly once");
        assertEquals("changed card text", gateway.editCalls.getLast().text());
        assertEquals(0, gateway.sendCalls.size());
        assertTrue(stoppedReasons.isEmpty());
    }

    @Test
    void anUnchangedCardStillStopsTheMonitorOnceTheObjectiveIsTerminal() {
        RecordingGateway gateway = new RecordingGateway();
        AtomicBoolean terminal = new AtomicBoolean(false);
        List<String> stoppedReasons = new ArrayList<>();
        TelegramWorkCardMonitor monitor = new TelegramWorkCardMonitor(
                gateway, "chat-1", "objective-1", 1000L,
                () -> "final card text", terminal::get, stoppedReasons::add);

        monitor.tick();
        terminal.set(true);
        monitor.tick();

        assertEquals(1, gateway.editCalls.size());
        assertTrue(monitor.stopped());
        assertEquals(List.of("refresh-succeeded-terminal"), stoppedReasons);
    }

    @Test
    void deterministicEditFailureTriggersAtMostOneBoundedReplacementThenStopsWithoutFurtherMessages() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.editFailure = telegramCantBeEditedFailure();
        List<String> stoppedReasons = new ArrayList<>();
        TelegramWorkCardMonitor monitor = new TelegramWorkCardMonitor(
                gateway, "chat-1", "objective-1", 1000L,
                () -> "card text", () -> false, stoppedReasons::add);

        for (int i = 0; i < 30; i++) monitor.tick();

        assertEquals(TelegramWorkCardMonitor.MAX_REPLACEMENTS, gateway.sendCalls.size(),
                "monitoring must never create an unbounded series of replacement messages -- the exact "
                        + "production incident was one new message roughly every 5 seconds, forever");
        assertEquals(1, gateway.sendCalls.size());
        assertTrue(monitor.stopped());
        assertEquals(List.of("refresh-failed-consecutive-limit"), stoppedReasons);
        assertEquals(21, gateway.editCalls.size(),
                "1 initial failure + MAX_CONSECUTIVE_FAILURES(20) further failures after the one "
                        + "replacement is spent, then the monitor must stop rather than retry forever");

        // The replacement itself must be editable: subsequent ticks keep editing its (new) message id.
        assertEquals(2000L, monitor.activeMessageId());
        assertTrue(gateway.editCalls.stream().skip(1).allMatch(call -> call.messageId() == 2000L),
                "every edit after the one replacement must target the replacement's own message id, "
                        + "proving the replacement is itself editable");
    }

    @Test
    void aReplacementThatCannotEvenBeSentIsStillOnlyAttemptedOnce() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.editFailure = telegramCantBeEditedFailure();
        gateway.sendFailure = new IllegalStateException("telegram_send_failed:telegram_error=400:Bad Request: chat not found");
        List<String> stoppedReasons = new ArrayList<>();
        TelegramWorkCardMonitor monitor = new TelegramWorkCardMonitor(
                gateway, "chat-1", "objective-1", 1000L,
                () -> "card text", () -> false, stoppedReasons::add);

        for (int i = 0; i < 30; i++) monitor.tick();

        assertEquals(1, gateway.sendCalls.size(),
                "a replacement that itself fails to send must never be retried");
        assertTrue(monitor.stopped());
        assertEquals(List.of("refresh-failed-consecutive-limit"), stoppedReasons);
        assertEquals(20, gateway.editCalls.size());
    }

    @Test
    void telegramFloodControlIsHonoredWithoutBurningTheReplacementOrGivingUp() {
        // Production 2026-09-24: every refresh hit "429 Too Many Requests: retry after N", the monitor
        // kept calling every 5 s (extending the ban), spent its one-time replacement on a 429 as well,
        // and the Work Cards stayed stale.
        RecordingGateway gateway = new RecordingGateway();
        gateway.editFailure = new IllegalStateException(
                "telegram_send_failed:telegram_error=429:Too Many Requests: retry after 37");
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        List<String> stoppedReasons = new ArrayList<>();
        TelegramWorkCardMonitor monitor = new TelegramWorkCardMonitor(
                gateway, "chat-1", "objective-1", 1000L,
                () -> "card text", () -> false, stoppedReasons::add, now::get);

        monitor.tick();
        for (int i = 0; i < 7; i++) {          // 35 s of 5 s ticks, all inside the 37 s wait
            now.addAndGet(5_000L);
            monitor.tick();
        }
        assertEquals(1, gateway.editCalls.size(), "no Telegram call may be made during the retry-after window");
        assertEquals(0, gateway.sendCalls.size(), "flood control must never spend the one-time replacement");
        assertFalse(monitor.stopped());

        gateway.editFailure = null;
        now.addAndGet(5_000L);                  // 40 s: wait elapsed
        monitor.tick();
        assertEquals(2, gateway.editCalls.size(), "refresh resumes once the retry-after window has elapsed");
        assertEquals(1000L, monitor.activeMessageId(), "the original card is still edited in place");
        assertTrue(stoppedReasons.isEmpty());
    }

    @Test
    void aRateLimitedEditNeverSendsAReplacementCard() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.editFailure = new IllegalStateException(
                "telegram_send_failed:telegram_error=429:Too Many Requests: retry after 43");
        List<String> stoppedReasons = new ArrayList<>();
        TelegramWorkCardMonitor monitor = new TelegramWorkCardMonitor(
                gateway, "chat-1", "objective-1", 1000L,
                () -> "card text", () -> false, stoppedReasons::add);

        for (int i = 0; i < 30; i++) monitor.tick();

        assertEquals(0, gateway.sendCalls.size(), "a 429 is not an uneditable card and must not trigger a replacement");
        assertEquals(1, gateway.editCalls.size(), "no further edit may be attempted inside the retry-after window");
        assertFalse(monitor.stopped(), "flood control must not count toward giving up");
    }

    private static IllegalStateException telegramCantBeEditedFailure() {
        return new IllegalStateException(
                "telegram_send_failed:telegram_error=400:Bad Request: message can't be edited");
    }

    private static final class RecordingGateway implements TelegramWorkCardMonitor.Gateway {
        record EditCall(String chatId, long messageId, String text) {}
        record SendCall(String chatId, String text) {}

        final List<EditCall> editCalls = new ArrayList<>();
        final List<SendCall> sendCalls = new ArrayList<>();
        RuntimeException editFailure;
        RuntimeException sendFailure;
        long nextMessageId = 2000L;

        @Override
        public void editWorkCard(String chatId, long messageId, String text) {
            editCalls.add(new EditCall(chatId, messageId, text));
            if (editFailure != null) throw editFailure;
        }

        @Override
        public long sendWorkCard(String chatId, String text) {
            sendCalls.add(new SendCall(chatId, text));
            if (sendFailure != null) throw sendFailure;
            return nextMessageId++;
        }
    }
}
