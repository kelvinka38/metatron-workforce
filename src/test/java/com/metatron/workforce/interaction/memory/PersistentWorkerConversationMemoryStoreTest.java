package com.metatron.workforce.interaction.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.workplace.WorkplaceWorkerChatStore;
import com.metatron.workforce.workplace.WorkerConversationGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersistentWorkerConversationMemoryStoreTest {
    @TempDir Path temp;

    @Test
    void sameHumanAndWorkerRememberAcrossWorkplaceAndTelegramButOtherWorkersStayIsolated() {
        Path root = temp.resolve("worker-memory");
        Path legacy = temp.resolve("legacy");
        PersistentWorkerConversationMemoryStore first =
                new PersistentWorkerConversationMemoryStore(new ObjectMapper(), root, legacy);

        first.append(
                "human-primary", "WORKER-GATEWAY-DIRECTOR", "workplace",
                "For the Gateway latency topic, remember threshold is 180 ms.",
                "Understood. I will use 180 ms as the threshold for this Gateway latency topic.",
                "req-1", "runtime-1", List.of("institutional-source:test"));

        PersistentWorkerConversationMemoryStore reloaded =
                new PersistentWorkerConversationMemoryStore(new ObjectMapper(), root, legacy);

        String telegramContext = reloaded.contextFor(
                "human-primary", "WORKER-GATEWAY-DIRECTOR",
                "What threshold did we agree for Gateway latency?",
                8, 8, 8000);

        assertTrue(telegramContext.contains("180 ms"));
        assertEquals(1, reloaded.turnCount("human-primary", "WORKER-GATEWAY-DIRECTOR"));
        assertTrue(reloaded.history("human-primary", "WORKER-OTHER").isEmpty());
        assertTrue(reloaded.history("human-other", "WORKER-GATEWAY-DIRECTOR").isEmpty());
    }

    @Test
    void importsExistingControlRoomWorkerHistoryForFounderOnce() {
        Path root = temp.resolve("worker-memory");
        Path legacy = temp.resolve("legacy");
        WorkplaceWorkerChatStore oldStore = new WorkplaceWorkerChatStore(new ObjectMapper(), legacy.toString());
        oldStore.append(
                "WORKER-GATEWAY-DIRECTOR",
                "Keep this migration note.",
                new WorkerConversationGateway.Reply(
                        "Migration note retained.",
                        "legacy-req",
                        List.of("worker:WORKER-GATEWAY-DIRECTOR:status=ACTIVE"),
                        "runtime-legacy"));

        PersistentWorkerConversationMemoryStore store =
                new PersistentWorkerConversationMemoryStore(new ObjectMapper(), root, legacy);

        var history = store.history("human-primary", "WORKER-GATEWAY-DIRECTOR");
        assertEquals(1, history.size());
        assertEquals("workplace-legacy", history.getFirst().channel());
        assertEquals("Migration note retained.", history.getFirst().worker());

        PersistentWorkerConversationMemoryStore reloaded =
                new PersistentWorkerConversationMemoryStore(new ObjectMapper(), root, legacy);
        assertEquals(1, reloaded.history("human-primary", "WORKER-GATEWAY-DIRECTOR").size());
    }
}
