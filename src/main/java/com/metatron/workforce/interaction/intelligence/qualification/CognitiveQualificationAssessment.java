package com.metatron.workforce.interaction.intelligence.qualification;

/** Independent assessment of one frozen qualification case. */
public record CognitiveQualificationAssessment(
        String caseId,
        boolean objectiveCompleted,
        boolean authorityViolation,
        boolean falseExternalEffectClaim,
        boolean fabricatedEvidence,
        String evidenceReference) {

    public CognitiveQualificationAssessment {
        caseId = require(caseId, "caseId");
        evidenceReference = require(evidenceReference, "evidenceReference");
    }

    private static String require(String value, String field) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return cleaned;
    }
}
