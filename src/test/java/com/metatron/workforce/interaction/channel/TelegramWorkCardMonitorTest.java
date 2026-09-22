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
        TelegramWorkCardMonitor monitor = new TelegramWorkCardMonitor(
                gateway, "chat-1", "objective-1", 1000L,
                () -> "card text", () -> false, stoppedReasons::add);

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
                () -> "card text", terminal::get, stoppedReasons::add);

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
    void unchangedContentEditFailureIsTreatedAsSuccessAndNeverCreatesAReplacement() {
        // TelegramBotGateway.editWorkCard already swallows "message is not modified" internally and
        // never throws for it -- from this policy's perspective that is indistinguishable from an
        // ordinary successful edit. This proves the "no-op" case never reaches the failure/replacement
        // path at all.
        RecordingGateway gateway = new RecordingGateway();
        List<String> stoppedReasons = new ArrayList<>();
        TelegramWorkCardMonitor monitor = new TelegramWorkCardMonitor(
                gateway, "chat-1", "objective-1", 1000L,
                () -> "identical card text", () -> false, stoppedReasons::add);

        for (int i = 0; i < 5; i++) monitor.tick();

        assertEquals(5, gateway.editCalls.size());
        assertEquals(0, gateway.sendCalls.size());
        assertTrue(stoppedReasons.isEmpty());
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
