package com.metatron.workforce.phase3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkplaceCommunicationServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void authorizedConversationAndMessageRemainAttributable() {
        ActorRef human = new ActorRef("human-1", ActorRef.ActorType.HUMAN);
        ActorRef head = new ActorRef("worker-head", ActorRef.ActorType.WORKER);
        WorkplaceCommunicationService service = new WorkplaceCommunicationService(
                (actor, target, org) -> AuthorizationContext.allowed("auth-1"), CLOCK);

        Conversation conversation = service.startConversation(
                human, List.of(human, head), "org-1");
        Message message = service.sendMessage(
                conversation.conversationId(), human, "content:hello", "org-1");

        assertEquals(human, conversation.initiator());
        assertEquals(human, message.sender());
        assertEquals("auth-1", message.authorizationId());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), message.createdAt());
    }

    @Test
    void deniedCommunicationDoesNotCreateConversation() {
        ActorRef human = new ActorRef("human-1", ActorRef.ActorType.HUMAN);
        ActorRef worker = new ActorRef("worker-1", ActorRef.ActorType.WORKER);
        WorkplaceCommunicationService service = new WorkplaceCommunicationService(
                (actor, target, org) -> AuthorizationContext.denied("auth-denied"), CLOCK);

        assertThrows(SecurityException.class, () -> service.startConversation(
                human, List.of(human, worker), "org-1"));
        assertEquals(0, service.conversations().size());
    }

    @Test
    void nonParticipantCannotSendMessage() {
        ActorRef human = new ActorRef("human-1", ActorRef.ActorType.HUMAN);
        ActorRef head = new ActorRef("worker-head", ActorRef.ActorType.WORKER);
        ActorRef outsider = new ActorRef("worker-outsider", ActorRef.ActorType.WORKER);
        WorkplaceCommunicationService service = new WorkplaceCommunicationService(
                (actor, target, org) -> AuthorizationContext.allowed("auth-1"), CLOCK);

        Conversation conversation = service.startConversation(
                human, List.of(human, head), "org-1");

        assertThrows(IllegalStateException.class, () -> service.sendMessage(
                conversation.conversationId(), outsider, "content:blocked", "org-1"));
    }
}
