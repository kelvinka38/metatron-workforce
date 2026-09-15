package com.metatron.workforce.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionObjectiveHandoff;
import com.metatron.workforce.interaction.intelligence.InMemoryIntelligenceCaseStore;
import com.metatron.workforce.interaction.intelligence.MetatronIntelligenceResponder;
import com.metatron.workforce.interaction.memory.ConversationMemoryStore;
import com.metatron.workforce.phase3.ActorRef;
import com.metatron.workforce.workplace.WorkplaceMeetingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class MetatronConversationRuntimeWorkMeetingObjectivePreemptionTest {
    private static final String CONVERSATION = "conversation:human:human-primary";

    @TempDir Path temp;

    @Test
    void activeMeetingOrdinaryMessageStaysInMeeting() {
        AtomicInteger handoffs = new AtomicInteger();
        WorkplaceMeetingService meeting = mock(WorkplaceMeetingService.class);
        when(meeting.hasActiveConversationMeeting(CONVERSATION)).thenReturn(true);
        when(meeting.handle(any(), anyString())).thenReturn("meeting-ok");

        MetatronConversationRuntime runtime = runtime(meeting, handoffs);
        var response = runtime.handle(interaction("Where are we now?", "telegram:update:ordinary"));

        assertEquals("meeting-ok", response.text());
        assertEquals("work:meeting-conversation", response.provenanceReference());
        assertEquals(0, handoffs.get());
    }

    @Test
    void activeMeetingExplicitObjectivePreemptsMeetingAndCreatesDurableObjective() {
        AtomicInteger handoffs = new AtomicInteger();
        WorkplaceMeetingService meeting = mock(WorkplaceMeetingService.class);
        MetatronConversationRuntime runtime = runtime(meeting, handoffs);
        String text = "Take ownership of one Objective: upgrade the local cognition model safely and verify production.";

        var response = runtime.handle(interaction(text, "telegram:update:objective"));

        assertEquals("work:interaction:telegram:update:objective", response.provenanceReference());
        assertTrue(response.text().startsWith("METATRON WORK ACCEPTED"));
        assertFalse(objectiveId(response.text()).isBlank());
        assertEquals(1, handoffs.get());
        verifyNoInteractions(meeting);
    }

    @Test
    void activeMeetingExplicitWorkerAssignmentPreemptsMeetingAndCreatesDurableObjective() {
        AtomicInteger handoffs = new AtomicInteger();
        WorkplaceMeetingService meeting = mock(WorkplaceMeetingService.class);
        MetatronConversationRuntime runtime = runtime(meeting, handoffs);
        String text = "Assign WORKER-GENERAL-ENGINEERING task: inspect and improve the bounded cognition runtime.";

        var response = runtime.handle(interaction(text, "telegram:update:worker-assignment"));

        assertEquals("work:interaction:telegram:update:worker-assignment", response.provenanceReference());
        assertTrue(response.text().startsWith("METATRON WORK ACCEPTED"));
        assertFalse(objectiveId(response.text()).isBlank());
        assertEquals(1, handoffs.get());
        verifyNoInteractions(meeting);
    }

    private MetatronConversationRuntime runtime(WorkplaceMeetingService meeting, AtomicInteger handoffs) {
        PersistentConversationSurfaceModeStore store = new PersistentConversationSurfaceModeStore(temp.resolve("surface"));
        store.set(CONVERSATION, ConversationSurfaceMode.WORK_MEETING);
        ConversationSurfaceModeService surface = new ConversationSurfaceModeService(store);

        ExecutionObjectiveHandoff handoff = (humanId, organizationContextId, caseId, conversationId,
                                              externalMessageReference, channel, request) -> {
            int sequence = handoffs.incrementAndGet();
            return new ExecutionObjectiveHandoff.HandoffReceipt(
                    true,
                    "objective:meeting-preemption:" + sequence,
                    "metatron-workforce",
                    "queue:meeting-preemption:" + sequence,
                    "ACCEPTED",
                    "ACCEPTED",
                    "OBJECTIVE_ACCEPTED_FOR_AUTONOMOUS_MANAGEMENT");
        };
        MetatronIntelligenceResponder responder = new MetatronIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper(),
                "", "", new InMemoryIntelligenceCaseStore(), handoff);

        ConversationMemoryStore memory = new ConversationMemoryStore() {
            @Override public String context(String conversationId, int maxTurns, int maxChars) { return "history"; }
            @Override public String contextFor(String conversationId, String currentText,
                                               int maxRecentTurns, int maxRelevantTurns, int maxChars) { return "history"; }
            @Override public void appendTurn(String conversationId, String humanText, String metatronText) { }
        };

        return new MetatronConversationRuntime(
                memory, responder, null, meeting, surface, null, null, 20, 20_000);
    }

    private static MetatronInteraction interaction(String text, String externalMessageReference) {
        return new MetatronInteraction(
                new ActorRef("human-primary", ActorRef.ActorType.HUMAN),
                new ActorRef("metatron-workforce", ActorRef.ActorType.WORKER),
                "organization:metatron",
                CONVERSATION,
                "telegram",
                "telegram:user:founder",
                "telegram:chat:founder",
                externalMessageReference,
                text);
    }

    private static String objectiveId(String answer) {
        return answer.lines()
                .filter(line -> line.startsWith("objective_id="))
                .map(line -> line.substring("objective_id=".length()).trim())
                .findFirst()
                .orElse("");
    }
}
