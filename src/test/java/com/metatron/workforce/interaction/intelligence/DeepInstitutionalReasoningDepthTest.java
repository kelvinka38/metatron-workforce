package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import com.metatron.workforce.phase3.ActorRef;
import com.metatron.workforce.phase3.AuthorizationContext;
import com.metatron.workforce.phase3.Conversation;
import com.metatron.workforce.phase3.Meeting;
import com.metatron.workforce.phase3.WorkplaceCommunicationService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DeepInstitutionalReasoningDepthTest {
    @Test
    void deepMeetingReasoningExpandsResourcesWithoutManufacturingHighConsequence() {
        AtomicReference<IntelligenceRequest> observed = new AtomicReference<>();
        IntelligenceFabric fabric = assertingFabric(observed);
        WorkplaceIntelligenceBridge bridge = new WorkplaceIntelligenceBridge(fabric);
        ActorRef worker = new ActorRef("worker-a", ActorRef.ActorType.WORKER);
        ActorRef human = new ActorRef("human-a", ActorRef.ActorType.HUMAN);
        Instant start = Instant.parse("2026-08-30T01:00:00Z");
        Meeting meeting = new Meeting(
                "meeting-deep", worker, List.of(worker, human), "org-1", "deep review", "evidence and risks",
                start, start.plusSeconds(3600), start, null, Meeting.MeetingState.HELD, null,
                List.of("discussion:deep"), List.of(), List.of(), List.of("evidence:deep"), start.minusSeconds(60));

        var receipt = bridge.analyzeMeeting(
                meeting, worker, "deeply investigate the disagreement", IntelligenceDepth.DEEP,
                CollaborationMode.SINGLE, List.of(LlmProvider.GOOGLE), 1);

        assertEquals("grounded-analysis", receipt.intelligenceResult().text());
        assertEquals("MEDIUM", observed.get().consequence());
        assertEquals("extended-investigation", observed.get().latencyBudget());
        assertEquals("deep", observed.get().costBudget());
        assertEquals("", observed.get().authorityContext());
    }

    @Test
    void deepWorkerEscalationExpandsResourcesWithoutManufacturingHighConsequence() {
        AtomicReference<IntelligenceRequest> observed = new AtomicReference<>();
        IntelligenceFabric fabric = assertingFabric(observed);
        Clock clock = Clock.fixed(Instant.parse("2026-08-30T02:00:00Z"), ZoneOffset.UTC);
        WorkplaceCommunicationService workplace = new WorkplaceCommunicationService(
                (actor, target, org) -> AuthorizationContext.allowed("auth-workplace-1"), clock);
        ActorRef workerA = new ActorRef("worker-a", ActorRef.ActorType.WORKER);
        ActorRef workerB = new ActorRef("worker-b", ActorRef.ActorType.WORKER);
        Conversation conversation = workplace.startConversation(workerA, List.of(workerA, workerB), "org-1");
        WorkerIntelligenceEscalationService service = new WorkerIntelligenceEscalationService(
                fabric, workplace, result -> "intelligence-artifact:" + result.requestId());

        var receipt = service.escalate(
                conversation, workerA, "deeply investigate this work product", IntelligenceDepth.DEEP,
                CollaborationMode.SINGLE, List.of(LlmProvider.GOOGLE), 1, List.of("evidence:work-product"));

        assertTrue(receipt.intelligenceArtifactReference().startsWith("intelligence-artifact:"));
        assertEquals("MEDIUM", observed.get().consequence());
        assertEquals("extended-investigation", observed.get().latencyBudget());
        assertEquals("deep", observed.get().costBudget());
        assertEquals("", observed.get().authorityContext());
    }

    private static IntelligenceFabric assertingFabric(AtomicReference<IntelligenceRequest> observed) {
        IntelligencePlanner planner = new IntelligencePlanner(request -> List.of(LlmProvider.GOOGLE));
        IntelligenceEngine engine = (provider, request) -> {
            observed.set(request);
            return new LlmResponse(provider, "test-model", "grounded-analysis", "provider-ref");
        };
        IntelligenceSynthesizer synthesizer = (request, responses) -> "unused";
        IntelligenceGovernance governance = (request, responses, finalText) -> {
            assertEquals("MEDIUM", request.consequence());
            assertEquals("", request.authorityContext());
        };
        return new IntelligenceFabric(planner, engine, synthesizer, governance);
    }
}
