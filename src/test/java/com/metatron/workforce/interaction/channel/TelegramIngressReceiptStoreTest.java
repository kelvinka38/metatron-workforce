package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TelegramIngressReceiptStoreTest {
    @TempDir Path tempDir;

    @Test
    void receivedAndAdmittedSurviveStoreReplacementBeforeProcessing() {
        Path state = tempDir.resolve("telegram-ingress.json");
        ObjectMapper mapper = new ObjectMapper();
        TelegramIngressReceiptStore first = new TelegramIngressReceiptStore(state, mapper);

        TelegramIngressReceiptStore.Receipt received = first.receive(101L, 77L, "77", "take ownership of this objective");
        assertEquals(TelegramIngressReceiptStore.Status.RECEIVED, received.status());
        first.admit(101L);

        TelegramIngressReceiptStore restored = new TelegramIngressReceiptStore(state, mapper);
        TelegramIngressReceiptStore.Receipt durable = restored.find(101L);
        assertNotNull(durable);
        assertEquals(TelegramIngressReceiptStore.Status.ADMITTED, durable.status());
        assertEquals("telegram:update:101", durable.externalMessageReference());
        assertEquals(List.of(101L), restored.recoverable(3).stream().map(TelegramIngressReceiptStore.Receipt::updateId).toList());
    }

    @Test
    void duplicateProviderDeliveryDoesNotCreateAnotherReceipt() {
        Path state = tempDir.resolve("telegram-ingress.json");
        TelegramIngressReceiptStore store = new TelegramIngressReceiptStore(state, new ObjectMapper());

        TelegramIngressReceiptStore.Receipt first = store.receive(202L, 77L, "77", "audit four repositories");
        TelegramIngressReceiptStore.Receipt duplicate = store.receive(202L, 77L, "77", "audit four repositories");

        assertEquals(first, duplicate);
        assertEquals(1, store.recoverable(3).size());
    }

    @Test
    void interruptedProcessingIsRecoverableAndAcceptedObjectiveCorrelationIsDurable() {
        Path state = tempDir.resolve("telegram-ingress.json");
        ObjectMapper mapper = new ObjectMapper();
        TelegramIngressReceiptStore store = new TelegramIngressReceiptStore(state, mapper);
        store.receive(303L, 77L, "77", "perform governed audit");
        store.admit(303L);
        TelegramIngressReceiptStore.Receipt processing = store.claim(303L, 3);
        assertEquals(TelegramIngressReceiptStore.Status.PROCESSING, processing.status());
        assertEquals(1, processing.attempts());

        TelegramIngressReceiptStore afterCrash = new TelegramIngressReceiptStore(state, mapper);
        assertEquals(303L, afterCrash.recoverable(3).getFirst().updateId());
        afterCrash.accepted(303L, "objective:test:303");

        TelegramIngressReceiptStore restored = new TelegramIngressReceiptStore(state, mapper);
        TelegramIngressReceiptStore.Receipt accepted = restored.find(303L);
        assertEquals(TelegramIngressReceiptStore.Status.ACCEPTED, accepted.status());
        assertEquals("objective:test:303", accepted.objectiveId());
        assertEquals(303L, restored.recoverable(3).getFirst().updateId());

        restored.delivered(303L);
        assertTrue(new TelegramIngressReceiptStore(state, mapper).recoverable(3).isEmpty());
    }

    @Test
    void boundedFailuresDeadLetterInsteadOfRetryingForever() {
        Path state = tempDir.resolve("telegram-ingress.json");
        TelegramIngressReceiptStore store = new TelegramIngressReceiptStore(state, new ObjectMapper());
        store.receive(404L, 77L, "77", "work");
        store.admit(404L);
        for (int attempt = 0; attempt < 3; attempt++) {
            store.claim(404L, 3);
            store.failed(404L, new IllegalStateException("provider timeout"), 3);
        }

        TelegramIngressReceiptStore.Receipt receipt = store.find(404L);
        assertEquals(TelegramIngressReceiptStore.Status.DEAD_LETTER, receipt.status());
        assertEquals(3, receipt.attempts());
        assertTrue(store.recoverable(3).isEmpty());
    }

    @Test
    void extractsCanonicalObjectiveIdFromHumanAcceptanceResponse() {
        String response = "METATRON WORK ACCEPTED\ncase_id=case:1\nobjective_id=objective:institutional:42\nowner_worker=manager";
        assertEquals("objective:institutional:42", TelegramWebhookController.objectiveIdFromAnswer(response));
        assertEquals("", TelegramWebhookController.objectiveIdFromAnswer("ordinary discussion"));
    }

    @Test
    void aDeliveryFailureAfterAdmissionReplaysOnlyDeliveryAndNeverAdmitsAnotherObjective() {
        // Production 2026-09-24: update 103338020 admitted case-11a08089, then every Telegram send failed on
        // flood control; each retry re-ran the interaction and admitted another case (952123b7, ee1d1fd3).
        TelegramIngressReceiptStore store = new TelegramIngressReceiptStore(tempDir.resolve("replay.json"), new ObjectMapper());
        store.receive(404L, 77L, "77", "build and deliver the control center");
        store.admit(404L);
        TelegramIngressReceiptStore.Receipt first = store.claim(404L, 3);
        assertEquals("", TelegramWebhookController.deliveryReplayObjectiveId(first), "first attempt runs the interaction");

        store.accepted(404L, "objective:case-11a08089");
        store.failed(404L, new IllegalStateException("telegram_send_failed:telegram_error=429:Too Many Requests: retry after 14083"), 3);
        TelegramIngressReceiptStore.Receipt retry = store.claim(404L, 3);

        assertEquals("objective:case-11a08089", TelegramWebhookController.deliveryReplayObjectiveId(retry),
                "the retry must redeliver the already-admitted Objective instead of re-running the interaction");
    }
}
