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

        // Production 2026-09-26, Telegram update 103338033: Intelligence rendered the objective id
        // as inline Markdown code. The transport correlation parser must normalize the presentation
        // wrapper instead of treating the backticks as part of the durable Objective identity.
        String markdown = "METATRON WORK ACCEPTED\nobjective_id=`objective:case-e1c8b586`\nowner_worker=manager";
        assertEquals("objective:case-e1c8b586", TelegramWebhookController.objectiveIdFromAnswer(markdown));

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

    @Test
    void floodControlDefersWorkCardDeliveryInsteadOfDeadLetteringTheAdmittedObjective() {
        // Production 2026-09-24: update 103338021 admitted case-b60eb63a, then three sends hit the local
        // flood-control gate within 1 s and the receipt was DEAD_LETTER; the Work Card was never delivered.
        TelegramIngressReceiptStore store = new TelegramIngressReceiptStore(tempDir.resolve("flood.json"), new ObjectMapper());
        store.receive(505L, 77L, "77", "build and deliver the control center");
        store.admit(505L);
        store.claim(505L, 3);
        store.accepted(505L, "objective:case-b60eb63a");
        IllegalStateException flood = new IllegalStateException(
                "telegram_send_failed:telegram_error=429:Too Many Requests: retry after 10879 (local flood-control gate)");

        assertTrue(TelegramWebhookController.deferDeliveryForFloodControl(store.find(505L), flood));
        for (int replay = 0; replay < 5; replay++) {
            store.deliveryDeferred(505L, flood);
            TelegramIngressReceiptStore.Receipt claimed = store.claim(505L, 3);
            assertEquals(TelegramIngressReceiptStore.Status.ACCEPTED, claimed.status(), "deferral never dead-letters");
            assertEquals("objective:case-b60eb63a", TelegramWebhookController.deliveryReplayObjectiveId(claimed));
        }
        assertEquals(1, store.find(505L).attempts(), "flood-control deferral consumes no processing attempt");
        assertTrue(store.recoverable(3).stream().anyMatch(r -> r.updateId() == 505L), "a restart replays the delivery");
        assertEquals(10_880_000L, TelegramWebhookController.floodControlReplayDelayMillis(10_879_000L));
        assertEquals(5_000L, TelegramWebhookController.floodControlReplayDelayMillis(0L));

        store.receive(506L, 77L, "77", "hello");
        store.admit(506L);
        assertFalse(TelegramWebhookController.deferDeliveryForFloodControl(store.find(506L), flood),
                "without an admitted Objective the interaction answer is not deferred");
        assertFalse(TelegramWebhookController.deferDeliveryForFloodControl(store.find(505L),
                new IllegalStateException("telegram_send_failed:telegram_error=400:Bad Request")));
    }

    @Test
    void recentFloodControlDeadLettersWithAnObjectiveAreRevivedForDelivery() {
        TelegramIngressReceiptStore store = new TelegramIngressReceiptStore(tempDir.resolve("revive.json"), new ObjectMapper());
        IllegalStateException flood = new IllegalStateException(
                "telegram_send_failed:telegram_error=429:Too Many Requests: retry after 10879 (local flood-control gate)");
        store.receive(601L, 77L, "77", "build it");
        store.admit(601L);
        store.claim(601L, 1);
        store.accepted(601L, "objective:case-flood");
        store.failed(601L, flood, 1);
        store.receive(602L, 77L, "77", "other");
        store.admit(602L);
        store.claim(602L, 1);
        store.accepted(602L, "objective:case-other");
        store.failed(602L, new IllegalStateException("telegram_send_failed:telegram_error=400:Bad Request"), 1);
        assertEquals(TelegramIngressReceiptStore.Status.DEAD_LETTER, store.find(601L).status());

        assertEquals(0, store.reviveFloodControlDeadLetters(System.currentTimeMillis() + 60_000L),
                "dead letters older than the revival window stay retained");
        assertEquals(1, store.reviveFloodControlDeadLetters(0L));
        assertEquals(TelegramIngressReceiptStore.Status.ACCEPTED, store.find(601L).status());
        assertEquals(TelegramIngressReceiptStore.Status.DEAD_LETTER, store.find(602L).status());

        TelegramIngressReceiptStore reloaded = new TelegramIngressReceiptStore(tempDir.resolve("revive.json"), new ObjectMapper());
        assertEquals(TelegramIngressReceiptStore.Status.ACCEPTED, reloaded.find(601L).status(), "revival is durable");
    }
}
