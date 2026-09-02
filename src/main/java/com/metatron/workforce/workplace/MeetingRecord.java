package com.metatron.workforce.workplace;

import java.util.List;
import java.util.Objects;

/** Durable institutional Meeting Room state. A meeting recommendation does not create authority. */
public record MeetingRecord(
        String meetingId,
        String organizationContextId,
        String conversationId,
        String channelProvider,
        String externalMessageReference,
        String title,
        String purpose,
        String organizer,
        List<String> participants,
        List<String> agenda,
        List<Contribution> contributions,
        String recommendation,
        List<String> actionItems,
        List<String> decisionRefs,
        List<String> evidenceRefs,
        List<String> lifecycle,
        Status status,
        String openedAt,
        String closedAt,
        boolean authorityCreated) {

    public MeetingRecord {
        require(meetingId, "meetingId");
        require(organizationContextId, "organizationContextId");
        require(conversationId, "conversationId");
        require(channelProvider, "channelProvider");
        require(externalMessageReference, "externalMessageReference");
        require(title, "title");
        require(purpose, "purpose");
        require(organizer, "organizer");
        participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        agenda = List.copyOf(Objects.requireNonNull(agenda, "agenda"));
        contributions = List.copyOf(Objects.requireNonNull(contributions, "contributions"));
        actionItems = List.copyOf(Objects.requireNonNull(actionItems, "actionItems"));
        decisionRefs = List.copyOf(Objects.requireNonNull(decisionRefs, "decisionRefs"));
        evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
        lifecycle = List.copyOf(Objects.requireNonNull(lifecycle, "lifecycle"));
        Objects.requireNonNull(status, "status");
        openedAt = openedAt == null ? "" : openedAt;
        closedAt = closedAt == null ? "" : closedAt;
        recommendation = recommendation == null ? "" : recommendation;
        if (participants.size() < 2) throw new IllegalArgumentException("meeting requires at least two participants");
        if (authorityCreated) throw new IllegalArgumentException("Meeting Room cannot create institutional authority");
    }

    public enum Status { PROPOSED, OPEN, ACTIVE, DECISION_PENDING, CLOSED, FOLLOW_UP }

    public record Contribution(String participant, String role, String text, String providerReference) {
        public Contribution {
            require(participant, "participant");
            require(role, "role");
            require(text, "text");
            providerReference = providerReference == null ? "" : providerReference;
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    }
}
