package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.AnalyticalProtocolType;
import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.DeterministicCapability;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepth;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.workplace.InMemoryWorkplaceContinuityStateStore;
import com.metatron.workforce.workplace.WorkplaceChannelAuthorizationVerifier;
import com.metatron.workforce.workplace.WorkplaceContinuityService;
import com.metatron.workforce.workplace.WorkplaceDelivery;
import com.metatron.workforce.workplace.WorkplaceDeliveryAdapter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossChannelWorkplaceContinuityTest {
    @Test
    void submitTelegramQuerySlackDeliverEmailPreservesInstitutionalIdentity() {
        Instant now = Instant.parse("2026-08-31T05:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomousExecutionCapability capability = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.audit.read"; }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                return new CapabilityResult(true, "worker-auditor", "assignment-1",
                        "work-1", List.of("evidence:1"), "PASS");
            }
        };
        AutonomousManagementRunner dormantRunner = new AutonomousManagementRunner(
                management, (caseId, request, available) -> request.executionWorkPlan(),
                List.of(capability), clock);

        WorkplaceChannelAuthorizationVerifier verifier = (objectiveId, humanId, channel, authorizationReference, purpose) ->
                humanId.equals("human-primary")
                        && ((purpose == WorkplaceChannelAuthorizationVerifier.Purpose.QUERY
                                && channel.equals("slack") && authorizationReference.equals("auth:slack:query"))
                            || (purpose == WorkplaceChannelAuthorizationVerifier.Purpose.DELIVERY
                                && channel.equals("email") && authorizationReference.equals("auth:email:delivery")));
        WorkplaceDeliveryAdapter email = new WorkplaceDeliveryAdapter() {
            @Override public boolean supports(String channel) { return channel.equals("email"); }
            @Override public String deliver(WorkplaceDelivery delivery) {
                assertEquals("conversation-canonical-1", delivery.conversationRef());
                return "email:message:final-1";
            }
        };
        WorkplaceContinuityService continuity = new WorkplaceContinuityService(
                new InMemoryWorkplaceContinuityStateStore(), management, List.of(verifier), List.of(email));
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(capability), dormantRunner, continuity, "worker-head", clock);

        var receipt = ingress.submit(
                "human-primary", "org-metatron", "case-cross-channel", "conversation-canonical-1",
                "telegram:update:100", "telegram", request());
        String objectiveId = receipt.objectiveId();

        assertEquals("conversation-canonical-1", continuity.continuity(objectiveId).conversationRef());
        assertEquals("telegram", continuity.continuity(objectiveId).ingressChannel());
        assertEquals("worker-head", management.get(objectiveId).ownerWorkerId());

        continuity.referenceMeeting(objectiveId, "workplace:meeting:42", now.plusSeconds(1));
        continuity.referenceDecision(objectiveId, "workplace:decision:7", now.plusSeconds(2));
        continuity.authorizeChannel(objectiveId, "human-primary", "slack", "auth:slack:query",
                WorkplaceChannelAuthorizationVerifier.Purpose.QUERY, now.plusSeconds(3));
        continuity.authorizeChannel(objectiveId, "human-primary", "email", "auth:email:delivery",
                WorkplaceChannelAuthorizationVerifier.Purpose.DELIVERY, now.plusSeconds(4));

        WorkplaceContinuityService.ProgressProjection projection = continuity.progress(
                objectiveId, "human-primary", "slack", "auth:slack:query");
        assertEquals(objectiveId, projection.objectiveId());
        assertEquals("worker-head", projection.ownerWorkerId());
        assertEquals("conversation-canonical-1", projection.conversationRef());
        assertEquals("telegram", projection.ingressChannel());
        assertEquals("slack", projection.queryChannel());
        assertEquals(List.of("workplace:meeting:42"), projection.meetingRefs());
        assertEquals(List.of("workplace:decision:7"), projection.decisionRefs());

        WorkplaceDelivery queued = continuity.queueDelivery(
                objectiveId, "human-primary", "email", "auth:email:delivery",
                "completion-package:" + objectiveId, now.plusSeconds(5));
        WorkplaceDelivery delivered = continuity.deliver(queued.deliveryId(), now.plusSeconds(6));

        assertEquals(WorkplaceDelivery.Status.DELIVERED, delivered.status());
        assertEquals("email:message:final-1", delivered.externalDeliveryRef());
        assertEquals(objectiveId, delivered.objectiveId());
        assertEquals("conversation-canonical-1", delivered.conversationRef());
        assertTrue(!delivered.channel().equals(continuity.continuity(objectiveId).ingressChannel()));
    }

    private static NormalizedRequest request() {
        ExecutionWorkSpec step = new ExecutionWorkSpec(
                "step-1", "Audit repository", "kelvinka38/metatron-workforce", "test.audit.read",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("audit result is complete"), List.of("independent repository evidence"));
        return new NormalizedRequest(
                "Audit repository", "metatron-workforce", List.of("read-only"), IntelligenceDepth.ANALYZE,
                "evidence-backed result", List.of(), List.of("do not mutate"), "current", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(step), false, null, LlmProvider.OPENAI, "");
    }
}
