package com.metatron.workforce.phase3;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 3 application boundary for attributable workplace communication.
 * Authorization is evaluated externally; this service only coordinates the result.
 */
public final class WorkplaceCommunicationService {
    private final AuthorizationPolicy authorizationPolicy;
    private final Clock clock;
    private final List<Conversation> conversations = new ArrayList<>();
    private final List<Message> messages = new ArrayList<>();

    public WorkplaceCommunicationService(AuthorizationPolicy authorizationPolicy, Clock clock) {
        this.authorizationPolicy = Objects.requireNonNull(authorizationPolicy, "authorizationPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Conversation startConversation(
            ActorRef initiator,
            List<ActorRef> participants,
            String organizationContextId) {
        requireParticipant(initiator, participants);
        ActorRef target = participants.stream()
                .filter(actor -> !actor.equals(initiator))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("conversation requires a target participant"));

        AuthorizationContext authorization = authorizationPolicy.authorize(
                initiator, target, organizationContextId);
        requireAllowed(authorization);

        Conversation conversation = new Conversation(
                UUID.randomUUID().toString(),
                initiator,
                participants,
                organizationContextId,
                Instant.now(clock));
        conversations.add(conversation);
        return conversation;
    }

    public Message sendMessage(
            String conversationId,
            ActorRef sender,
            String contentReference,
            String organizationContextId) {
        Conversation conversation = conversations.stream()
                .filter(item -> item.conversationId().equals(conversationId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("conversation not found: " + conversationId));

        if (!conversation.participants().contains(sender)) {
            throw new IllegalStateException("sender is not a conversation participant");
        }
        if (!conversation.organizationContextId().equals(organizationContextId)) {
            throw new IllegalArgumentException("organization context does not match conversation context");
        }

        ActorRef target = conversation.participants().stream()
                .filter(actor -> !actor.equals(sender))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("conversation has no target participant"));

        AuthorizationContext authorization = authorizationPolicy.authorize(
                sender, target, conversation.organizationContextId());
        requireAllowed(authorization);

        Message message = new Message(
                UUID.randomUUID().toString(),
                conversationId,
                sender,
                contentReference,
                conversation.organizationContextId(),
                authorization.authorizationId(),
                Instant.now(clock));
        messages.add(message);
        return message;
    }

    public List<Conversation> conversations() {
        return List.copyOf(conversations);
    }

    public List<Message> messages() {
        return List.copyOf(messages);
    }

    private static void requireParticipant(ActorRef initiator, List<ActorRef> participants) {
        Objects.requireNonNull(initiator, "initiator");
        Objects.requireNonNull(participants, "participants");
        if (!participants.contains(initiator)) {
            throw new IllegalArgumentException("initiator must be a conversation participant");
        }
    }

    private static void requireAllowed(AuthorizationContext authorization) {
        Objects.requireNonNull(authorization, "authorization");
        if (!authorization.allowed()) {
            throw new SecurityException("workplace action denied by authorization context "
                    + authorization.authorizationId());
        }
    }
}
