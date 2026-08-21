package com.metatron.workforce.phase3;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Meeting(
        String meetingId,
        ActorRef organizer,
        List<ActorRef> participants,
        String organizationContextId,
        String purpose,
        String agenda,
        Instant scheduledStart,
        Instant scheduledEnd,
        Instant actualStart,
        Instant actualEnd,
        MeetingState state,
        String minutesReference,
        List<String> discussionReferences,
        List<String> decisionReferences,
        List<String> actionItemReferences,
        List<String> evidenceReferences,
        Instant createdAt) {

    public Meeting {
        Objects.requireNonNull(meetingId, "meetingId");
        Objects.requireNonNull(organizer, "organizer");
        Objects.requireNonNull(participants, "participants");
        Objects.requireNonNull(organizationContextId, "organizationContextId");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(agenda, "agenda");
        Objects.requireNonNull(scheduledStart, "scheduledStart");
        Objects.requireNonNull(scheduledEnd, "scheduledEnd");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        if (meetingId.isBlank()) throw new IllegalArgumentException("meetingId must not be blank");
        if (organizationContextId.isBlank()) throw new IllegalArgumentException("organizationContextId must not be blank");
        if (scheduledEnd.isBefore(scheduledStart)) throw new IllegalArgumentException("scheduledEnd must not precede scheduledStart");
        participants = List.copyOf(participants);
        discussionReferences = List.copyOf(Objects.requireNonNull(discussionReferences, "discussionReferences"));
        decisionReferences = List.copyOf(Objects.requireNonNull(decisionReferences, "decisionReferences"));
        actionItemReferences = List.copyOf(Objects.requireNonNull(actionItemReferences, "actionItemReferences"));
        evidenceReferences = List.copyOf(Objects.requireNonNull(evidenceReferences, "evidenceReferences"));
    }

    public enum MeetingState {
        PLANNED,
        SCHEDULED,
        HELD,
        MINUTES_PENDING,
        CLOSED,
        CANCELLED
    }
}
