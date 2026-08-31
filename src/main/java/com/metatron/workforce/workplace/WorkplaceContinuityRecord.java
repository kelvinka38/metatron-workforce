package com.metatron.workforce.workplace;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Workforce-side references into canonical Workplace identity.
 * Conversation, Meeting and Decision remain Workplace-owned objects; this record never redefines them.
 */
public record WorkplaceContinuityRecord(
        String objectiveId,
        String humanId,
        String conversationRef,
        String ingressChannel,
        String ingressMessageRef,
        String requestAdmissionRef,
        Map<String, String> channelAuthorizationRefs,
        List<String> meetingRefs,
        List<String> decisionRefs,
        List<String> evidenceRefs,
        Instant createdAt,
        Instant updatedAt) {
    public WorkplaceContinuityRecord {
        require(objectiveId, "objectiveId");
        require(humanId, "humanId");
        require(conversationRef, "conversationRef");
        require(ingressChannel, "ingressChannel");
        require(ingressMessageRef, "ingressMessageRef");
        require(requestAdmissionRef, "requestAdmissionRef");
        channelAuthorizationRefs = channelAuthorizationRefs == null
                ? Map.of() : Map.copyOf(new LinkedHashMap<>(channelAuthorizationRefs));
        meetingRefs = normalize(meetingRefs);
        decisionRefs = normalize(decisionRefs);
        evidenceRefs = normalize(evidenceRefs);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }
}
