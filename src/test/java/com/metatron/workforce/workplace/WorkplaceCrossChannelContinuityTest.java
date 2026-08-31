package com.metatron.workforce.workplace;

import com.metatron.workforce.management.ManagementAutonomyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkplaceCrossChannelContinuityTest {
    @TempDir Path temp;

    @Test
    void acceptedObjectiveCanBeQueriedAndDeliveredAcrossDifferentAuthorizedChannelsAfterServiceReplacement() {
        Instant acceptedAt = Instant.parse("2026-08-31T07:00:00Z");
        String objectiveId = "objective-cross-channel";
        String humanId = "human-founder";
        String conversationRef = "workplace:conversation:institutional-42";
        Path statePath = temp.resolve("workplace-continuity.json");

        ManagementAutonomyService management = new ManagementAutonomyService();
        management.acceptObjective(objectiveId, "worker-manager-1", "org-metatron",
                "Complete Workforce autonomy closure", acceptedAt);

        WorkplaceChannelAuthorizationVerifier verifier = (objective, human, channel, authorization, purpose) ->
                objectiveId.equals(objective)
                        && humanId.equals(human)
                        && ((purpose == WorkplaceChannelAuthorizationVerifier.Purpose.QUERY
                                && channel.equals("chatgpt") && authorization.equals("auth-query-chatgpt"))
                            || (purpose == WorkplaceChannelAuthorizationVerifier.Purpose.DELIVERY
                                && channel.equals("zalo") && authorization.equals("auth-delivery-zalo")));

        WorkplaceContinuityService ingress = new WorkplaceContinuityService(
                new FileWorkplaceContinuityStateStore(statePath), management, List.of(verifier), List.of());
        ingress.bindAcceptedObjective(objectiveId, humanId, conversationRef,
                "telegram", "telegram:message:1001", "gateway:admission:1001", acceptedAt.plusSeconds(1));
        ingress.authorizeChannel(objectiveId, humanId, "chatgpt", "auth-query-chatgpt",
                WorkplaceChannelAuthorizationVerifier.Purpose.QUERY, acceptedAt.plusSeconds(2));
        ingress.authorizeChannel(objectiveId, humanId, "zalo", "auth-delivery-zalo",
                WorkplaceChannelAuthorizationVerifier.Purpose.DELIVERY, acceptedAt.plusSeconds(3));
        ingress.referenceMeeting(objectiveId, "workplace:meeting:autonomy-review", acceptedAt.plusSeconds(4));
        ingress.referenceDecision(objectiveId, "workplace:decision:founder-approved", acceptedAt.plusSeconds(5));

        AtomicReference<WorkplaceDelivery> transported = new AtomicReference<>();
        WorkplaceDeliveryAdapter zalo = new WorkplaceDeliveryAdapter() {
            @Override public boolean supports(String channel) { return "zalo".equals(channel); }
            @Override public String deliver(WorkplaceDelivery delivery) {
                transported.set(delivery);
                return "zalo:delivery:9001";
            }
        };

        WorkplaceContinuityService replacement = new WorkplaceContinuityService(
                new FileWorkplaceContinuityStateStore(statePath), management, List.of(verifier), List.of(zalo));

        WorkplaceContinuityService.ProgressProjection progress = replacement.progress(
                objectiveId, humanId, "chatgpt", "auth-query-chatgpt");
        assertEquals(objectiveId, progress.objectiveId());
        assertEquals("worker-manager-1", progress.ownerWorkerId());
        assertEquals(conversationRef, progress.conversationRef());
        assertEquals("telegram", progress.ingressChannel());
        assertEquals("chatgpt", progress.queryChannel());
        assertTrue(progress.meetingRefs().contains("workplace:meeting:autonomy-review"));
        assertTrue(progress.decisionRefs().contains("workplace:decision:founder-approved"));

        WorkplaceDelivery queued = replacement.queueDelivery(objectiveId, humanId, "zalo",
                "auth-delivery-zalo", "closure-package:objective-cross-channel", acceptedAt.plusSeconds(6));
        WorkplaceDelivery delivered = replacement.deliver(queued.deliveryId(), acceptedAt.plusSeconds(7));

        assertEquals(WorkplaceDelivery.Status.DELIVERED, delivered.status());
        assertEquals("zalo:delivery:9001", delivered.externalDeliveryRef());
        assertEquals(objectiveId, transported.get().objectiveId());
        assertEquals(humanId, transported.get().humanId());
        assertEquals(conversationRef, transported.get().conversationRef());
        assertEquals("zalo", transported.get().channel());
        assertEquals("telegram", replacement.continuity(objectiveId).ingressChannel());
        assertEquals(1, replacement.deliveries(objectiveId).size());

        assertThrows(SecurityException.class, () -> replacement.progress(
                objectiveId, "different-human", "chatgpt", "auth-query-chatgpt"));
        assertThrows(SecurityException.class, () -> replacement.queueDelivery(
                objectiveId, humanId, "telegram", "auth-delivery-zalo", "payload:forbidden", acceptedAt.plusSeconds(8)));
    }
}
