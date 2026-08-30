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

import static org.junit.jupiter.api.Assertions.*;

final class WorkplaceIntelligenceIntegrationTest {
    @Test
    void meetingIntelligenceConsumesWorkplaceStateWithoutMutatingOrCreatingAuthority() {
        IntelligenceFabric fabric = fabric();
        WorkplaceIntelligenceBridge bridge = new WorkplaceIntelligenceBridge(fabric);
        ActorRef worker = new ActorRef("worker-a", ActorRef.ActorType.WORKER);
        ActorRef human = new ActorRef("human-a", ActorRef.ActorType.HUMAN);
        Instant start = Instant.parse("2026-08-30T01:00:00Z");
        Meeting meeting = new Meeting(
                "meeting-1", worker, List.of(worker, human), "org-1", "review performance", "evidence and causes",
                start, start.plusSeconds(3600), start, null, Meeting.MeetingState.HELD, null,
                List.of("discussion:1"), List.of(), List.of(), List.of("evidence:metric-1"), start.minusSeconds(60));

        var receipt = bridge.analyzeMeeting(meeting, worker, "identify material disagreement",
                IntelligenceDepth.ANALYZE, CollaborationMode.SINGLE, List.of(LlmProvider.GOOGLE), 1);

        assertEquals("meeting:meeting-1", receipt.meetingReference());
        assertEquals("org-1", receipt.organizationContextId());
        assertEquals("grounded-analysis", receipt.intelligenceResult().text());
        assertEquals(Meeting.MeetingState.HELD, meeting.state());
        assertEquals(List.of(), meeting.decisionReferences());
    }

    @Test
    void workerEscalationPublishesResultReferenceThroughAuthorizedWorkplaceMessage() {
        IntelligenceFabric fabric = fabric();
        Clock clock = Clock.fixed(Instant.parse("2026-08-30T02:00:00Z"), ZoneOffset.UTC);
        WorkplaceCommunicationService workplace = new WorkplaceCommunicationService(
                (actor, target, org) -> AuthorizationContext.allowed("auth-workplace-1"), clock);
        ActorRef workerA = new ActorRef("worker-a", ActorRef.ActorType.WORKER);
        ActorRef workerB = new ActorRef("worker-b", ActorRef.ActorType.WORKER);
        Conversation conversation = workplace.startConversation(workerA, List.of(workerA, workerB), "org-1");
        WorkerIntelligenceEscalationService service = new WorkerIntelligenceEscalationService(
                fabric, workplace, result -> "intelligence-artifact:" + result.requestId());

        var receipt = service.escalate(conversation, workerA, "review this evidence independently",
                IntelligenceDepth.ANALYZE, CollaborationMode.SINGLE, List.of(LlmProvider.GOOGLE), 1,
                List.of("evidence:work-product-1"));

        assertEquals("worker:worker-a", receipt.workerReference());
        assertEquals(workerA, receipt.workplaceMessage().sender());
        assertEquals("auth-workplace-1", receipt.workplaceMessage().authorizationId());
        assertTrue(receipt.intelligenceArtifactReference().startsWith("intelligence-artifact:"));
        assertEquals(1, workplace.messages().size());
    }

    @Test
    void workerEscalationFailsClosedWhenWorkplaceAuthorizationRejectsPublication() {
        IntelligenceFabric fabric = fabric();
        Clock clock = Clock.fixed(Instant.parse("2026-08-30T02:00:00Z"), ZoneOffset.UTC);
        ActorRef workerA = new ActorRef("worker-a", ActorRef.ActorType.WORKER);
        ActorRef workerB = new ActorRef("worker-b", ActorRef.ActorType.WORKER);
        final boolean[] allow = {true};
        WorkplaceCommunicationService workplace = new WorkplaceCommunicationService(
                (actor, target, org) -> allow[0]
                        ? AuthorizationContext.allowed("auth-open")
                        : AuthorizationContext.denied("auth-denied"), clock);
        Conversation conversation = workplace.startConversation(workerA, List.of(workerA, workerB), "org-1");
        allow[0] = false;
        WorkerIntelligenceEscalationService service = new WorkerIntelligenceEscalationService(
                fabric, workplace, result -> "intelligence-artifact:" + result.requestId());

        assertThrows(SecurityException.class, () -> service.escalate(
                conversation, workerA, "review", IntelligenceDepth.ANALYZE,
                CollaborationMode.SINGLE, List.of(LlmProvider.GOOGLE), 1, List.of()));
        assertEquals(0, workplace.messages().size());
    }

    private static IntelligenceFabric fabric() {
        IntelligencePlanner planner = new IntelligencePlanner(request -> List.of(LlmProvider.GOOGLE));
        IntelligenceEngine engine = (provider, request) -> new LlmResponse(
                provider, "test-model", "grounded-analysis", "provider-ref");
        IntelligenceSynthesizer synthesizer = (request, responses) -> "unused";
        IntelligenceGovernance governance = (request, responses, finalText) -> {};
        return new IntelligenceFabric(planner, engine, synthesizer, governance);
    }
}
