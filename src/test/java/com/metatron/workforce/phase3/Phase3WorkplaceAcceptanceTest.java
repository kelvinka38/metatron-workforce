package com.metatron.workforce.phase3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class Phase3WorkplaceAcceptanceTest {
    private static final Instant NOW = Instant.parse("2026-08-24T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ActorRef HUMAN = new ActorRef("human-1", ActorRef.ActorType.HUMAN);
    private static final ActorRef HEAD = new ActorRef("head-1", ActorRef.ActorType.WORKER);
    private static final ActorRef SUBORDINATE = new ActorRef("worker-1", ActorRef.ActorType.WORKER);
    private static final ActorRef PEER = new ActorRef("worker-2", ActorRef.ActorType.WORKER);

    private static AuthorizationPolicy allowAll() {
        return (actor, target, organization) -> AuthorizationContext.allowed(
                "auth-" + actor.actorId() + "-" + target.actorId());
    }

    @Test
    void provesHumanAndWorkerCommunicationDirectionsWithAttribution() {
        WorkplaceCommunicationService service = new WorkplaceCommunicationService(allowAll(), CLOCK);

        Conversation humanToHead = service.startConversation(HUMAN, List.of(HUMAN, HEAD), "org-1");
        Message request = service.sendMessage(humanToHead.conversationId(), HUMAN, "request-content", "org-1");
        Message response = service.sendMessage(humanToHead.conversationId(), HEAD, "response-content", "org-1");

        Conversation humanToSubordinate = service.startConversation(HUMAN, List.of(HUMAN, SUBORDINATE), "org-1");
        service.sendMessage(humanToSubordinate.conversationId(), HUMAN, "subordinate-request", "org-1");

        Conversation workerToWorker = service.startConversation(SUBORDINATE, List.of(SUBORDINATE, PEER), "org-1");
        service.sendMessage(workerToWorker.conversationId(), SUBORDINATE, "peer-request", "org-1");
        service.sendMessage(workerToWorker.conversationId(), PEER, "peer-response", "org-1");

        assertEquals(HUMAN, request.sender());
        assertEquals(HEAD, response.sender());
        assertEquals("org-1", response.organizationContextId());
        assertTrue(response.authorizationId().startsWith("auth-head-1-worker-1"));
        assertEquals(5, service.messages().size());
    }

    @Test
    void rejectsUnauthorizedCommunicationAndNonParticipants() {
        AuthorizationPolicy selective = (actor, target, organization) ->
                actor.equals(HUMAN) && target.equals(HEAD)
                        ? AuthorizationContext.allowed("auth-ok")
                        : AuthorizationContext.denied("auth-denied");
        WorkplaceCommunicationService service = new WorkplaceCommunicationService(selective, CLOCK);

        Conversation conversation = service.startConversation(HUMAN, List.of(HUMAN, HEAD), "org-1");
        assertThrows(SecurityException.class,
                () -> service.sendMessage(conversation.conversationId(), HEAD, "response", "org-1"));
        assertThrows(IllegalStateException.class,
                () -> service.sendMessage(conversation.conversationId(), SUBORDINATE, "intrusion", "org-1"));
        assertEquals(0, service.messages().size());
    }

    @Test
    void provesMeetingCoordinationWithoutManufacturingAuthority() {
        MeetingService service = new MeetingService(allowAll(), CLOCK);
        Meeting meeting = service.createMeeting(
                HUMAN, List.of(HUMAN, HEAD, SUBORDINATE), "org-1",
                "strategic coordination", "agenda-1", NOW.plusSeconds(3600), NOW.plusSeconds(7200));

        assertEquals(Meeting.MeetingState.SCHEDULED, meeting.state());
        meeting = service.holdMeeting(meeting.meetingId(), HUMAN, NOW.plusSeconds(3600));
        assertEquals(Meeting.MeetingState.HELD, meeting.state());
        meeting = service.closeMeeting(meeting.meetingId(), HUMAN, NOW.plusSeconds(7200), "minutes-1");

        assertEquals(Meeting.MeetingState.CLOSED, meeting.state());
        assertEquals("minutes-1", meeting.minutesReference());
        assertEquals(1, service.meetings().size());
    }

    @Test
    void provesInstitutionalQueueCapabilitiesAndOutstandingWork() {
        WorkQueueService service = new WorkQueueService(allowAll(), CLOCK);
        Set<WorkQueueItem.ItemType> required = Set.of(
                WorkQueueItem.ItemType.REQUEST,
                WorkQueueItem.ItemType.ASSIGNMENT_REFERENCE,
                WorkQueueItem.ItemType.APPROVAL_REQUEST,
                WorkQueueItem.ItemType.REVIEW_REQUEST,
                WorkQueueItem.ItemType.REPORT,
                WorkQueueItem.ItemType.ESCALATION,
                WorkQueueItem.ItemType.MEETING_INVITATION,
                WorkQueueItem.ItemType.PROPOSAL,
                WorkQueueItem.ItemType.NOTIFICATION);

        for (WorkQueueItem.ItemType type : required) {
            service.create(HEAD, "org-1", HUMAN, type, type.name().toLowerCase(),
                    WorkQueueItem.Priority.NORMAL, NOW.plusSeconds(3600));
        }

        assertEquals(required, service.items().stream()
                .map(WorkQueueItem::itemType)
                .collect(Collectors.toSet()));
        assertEquals(required.size(), service.outstandingAt(NOW).size());
    }

    @Test
    void queueTransitionsRetainReferenceAndAuthorizationAndDoNotCreateDomainAuthority() {
        WorkQueueService service = new WorkQueueService(
                (actor, target, organization) -> AuthorizationContext.allowed("auth-work-1"), CLOCK);
        WorkQueueItem item = service.create(
                HEAD, "org-1", HUMAN, WorkQueueItem.ItemType.ASSIGNMENT_REFERENCE,
                "authoritative-assignment-42", WorkQueueItem.Priority.HIGH, NOW.minusSeconds(1));

        item = service.deliver(item.queueItemId());
        item = service.acknowledge(item.queueItemId());
        item = service.escalate(item.queueItemId());

        assertEquals("authoritative-assignment-42", item.referencedObjectId());
        assertEquals("auth-work-1", item.authorizationId());
        assertEquals(WorkQueueItem.QueueState.ESCALATED, item.state());
        assertTrue(service.isOverdue(item, NOW));
    }

    @Test
    void deniedQueueCreationCannotEnterInstitutionalState() {
        WorkQueueService service = new WorkQueueService(
                (actor, target, organization) -> AuthorizationContext.denied("auth-denied"), CLOCK);

        assertThrows(SecurityException.class, () -> service.create(
                HEAD, "org-1", HUMAN, WorkQueueItem.ItemType.REQUEST,
                "work-1", WorkQueueItem.Priority.NORMAL, null));
        assertTrue(service.items().isEmpty());
    }
}
