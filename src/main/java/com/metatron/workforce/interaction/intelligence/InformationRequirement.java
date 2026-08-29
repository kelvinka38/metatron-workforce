package com.metatron.workforce.interaction.intelligence;

import java.util.List;
import java.util.Objects;

/** Case-local requirement for information needed to answer one bounded intelligence problem. */
public record InformationRequirement(
        String requirementId,
        String question,
        String reasonRequired,
        InformationRequirementStatus status,
        List<String> preferredSourceClasses,
        List<String> evidenceReferences,
        String freshnessRequirement,
        String qualityRequirement,
        String acquisitionCostHint,
        String latencyHint,
        String authorityRequirement,
        String impactIfUnknown) {

    public InformationRequirement {
        Objects.requireNonNull(requirementId, "requirementId");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(reasonRequired, "reasonRequired");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(preferredSourceClasses, "preferredSourceClasses");
        Objects.requireNonNull(evidenceReferences, "evidenceReferences");
        Objects.requireNonNull(freshnessRequirement, "freshnessRequirement");
        Objects.requireNonNull(qualityRequirement, "qualityRequirement");
        Objects.requireNonNull(acquisitionCostHint, "acquisitionCostHint");
        Objects.requireNonNull(latencyHint, "latencyHint");
        Objects.requireNonNull(authorityRequirement, "authorityRequirement");
        Objects.requireNonNull(impactIfUnknown, "impactIfUnknown");
        preferredSourceClasses = List.copyOf(preferredSourceClasses);
        evidenceReferences = List.copyOf(evidenceReferences);
        if (requirementId.isBlank() || question.isBlank()) {
            throw new IllegalArgumentException("requirementId and question must not be blank");
        }
    }

    public InformationRequirement withResolution(InformationRequirementStatus nextStatus, List<String> evidenceRefs) {
        Objects.requireNonNull(nextStatus, "nextStatus");
        return new InformationRequirement(
                requirementId, question, reasonRequired, nextStatus, preferredSourceClasses,
                evidenceRefs == null ? evidenceReferences : List.copyOf(evidenceRefs),
                freshnessRequirement, qualityRequirement, acquisitionCostHint, latencyHint,
                authorityRequirement, impactIfUnknown);
    }
}
