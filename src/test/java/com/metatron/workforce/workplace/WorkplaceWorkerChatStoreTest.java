package com.metatron.workforce.workplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkplaceWorkerChatStoreTest {
    @TempDir Path temp;

    @Test
    void directWorkerConversationHistoryIsDurableAndRenderedAsContext() {
        WorkplaceWorkerChatStore first = new WorkplaceWorkerChatStore(new ObjectMapper(), temp.toString());
        first.append("WORKER-1", "What are you working on?",
                new WorkerConversationGateway.Reply(
                        "Objective A is currently EXECUTING according to canonical Workforce state.",
                        "worker-cognition-1",
                        List.of("worker:WORKER-1:status=ACTIVE"),
                        "runtime-1"));

        WorkplaceWorkerChatStore reloaded = new WorkplaceWorkerChatStore(new ObjectMapper(), temp.toString());
        var history = reloaded.history("WORKER-1");

        assertEquals(1, history.size());
        assertEquals("What are you working on?", history.getFirst().human());
        assertEquals("runtime-1", history.getFirst().runtimeId());
        assertTrue(reloaded.context("WORKER-1", 5, 4000).contains("Objective A is currently EXECUTING"));
    }
}
