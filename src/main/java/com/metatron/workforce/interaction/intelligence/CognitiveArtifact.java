package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Provider-neutral reusable cognitive output owned by Metatron. */
public record CognitiveArtifact(
        String artifactId,
        String caseRef,
        String artifactType,
        String inputFingerprint,
        LlmProvider provider,
        String model,
        String result,
        List<String> claims,
        List<String> evidenceRefs,
        List<String> uncertainties,
        Instant createdAt,
        Instant validUntil,
        boolean reusable) {

    public CognitiveArtifact {
        artifactId = required(artifactId, "artifactId");
        caseRef = optional(caseRef);
        artifactType = required(artifactType, "artifactType");
        inputFingerprint = required(inputFingerprint, "inputFingerprint");
        Objects.requireNonNull(provider, "provider");
        model = required(model, "model");
        result = required(result, "result");
        claims = claims == null ? List.of() : List.copyOf(claims);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        uncertainties = uncertainties == null ? List.of() : List.copyOf(uncertainties);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (validUntil != null && validUntil.isBefore(createdAt)) {
            throw new IllegalArgumentException("validUntil cannot precede createdAt");
        }
    }

    public boolean validAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return reusable && (validUntil == null || !validUntil.isBefore(instant));
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }
}
