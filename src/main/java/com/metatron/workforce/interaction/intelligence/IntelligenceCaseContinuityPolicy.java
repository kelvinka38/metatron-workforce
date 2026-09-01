package com.metatron.workforce.interaction.intelligence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shared bounded-Case update rules for durable and in-memory stores. */
final class IntelligenceCaseContinuityPolicy {
    private IntelligenceCaseContinuityPolicy() {}

    static boolean startsNewCase(IntelligenceCase existing, NormalizedRequest normalized) {
        return existing == null
                || existing.status() == IntelligenceCaseStatus.RESOLVED
                || normalized.caseContinuity() == CaseContinuity.NEW
                // A current-external answer is a new evidence observation, even when the Human asks
                // about the same subject again. Conversation history remains available to semantics,
                // but evidence/requirements from the previous observation must not bleed into this one.
                || normalized.freshExternalDataRequired();
    }

    static List<InformationRequirement> mergeRequirements(IntelligenceCase existing,
                                                          List<InformationRequirement> planned,
                                                          NormalizedRequest normalized) {
        if (existing == null || existing.status() == IntelligenceCaseStatus.RESOLVED) return List.copyOf(planned);
        if (planned.isEmpty()) return existing.informationRequirements();

        Map<String, InformationRequirement> remainingPlanned = new LinkedHashMap<>();
        for (InformationRequirement requirement : planned) {
            remainingPlanned.put(key(requirement.question()), requirement);
        }

        List<InformationRequirement> merged = new ArrayList<>();
        Set<String> usedIds = new LinkedHashSet<>();
        for (InformationRequirement prior : existing.informationRequirements()) {
            usedIds.add(prior.requirementId());
            InformationRequirement current = remainingPlanned.remove(key(prior.question()));
            if (current != null) {
                merged.add(needsFreshAcquisition(current, normalized) ? uniqueId(current, usedIds) : prior);
            } else if (prior.status() == InformationRequirementStatus.MISSING
                    || prior.status() == InformationRequirementStatus.CONFLICTED) {
                merged.add(prior.withResolution(InformationRequirementStatus.DEFERRED, prior.evidenceReferences()));
            } else {
                merged.add(prior);
            }
        }

        for (InformationRequirement requirement : remainingPlanned.values()) {
            InformationRequirement unique = uniqueId(requirement, usedIds);
            merged.add(unique);
            usedIds.add(unique.requirementId());
        }
        return List.copyOf(merged);
    }

    private static boolean needsFreshAcquisition(InformationRequirement requirement, NormalizedRequest normalized) {
        if (!normalized.freshExternalDataRequired()) return false;
        String freshness = requirement.freshnessRequirement().toLowerCase(Locale.ROOT);
        if (freshness.contains("current") || freshness.contains("recent") || freshness.contains("real-time")
                || freshness.contains("realtime")) return true;
        return requirement.preferredSourceClasses().stream().anyMatch(source -> {
            String value = source.toLowerCase(Locale.ROOT);
            return value.contains("external") || value.contains("web");
        });
    }

    private static InformationRequirement uniqueId(InformationRequirement requirement, Set<String> usedIds) {
        if (!usedIds.contains(requirement.requirementId())) return requirement;
        return new InformationRequirement(
                "ir-" + UUID.randomUUID(),
                requirement.question(),
                requirement.reasonRequired(),
                requirement.status(),
                requirement.preferredSourceClasses(),
                requirement.evidenceReferences(),
                requirement.freshnessRequirement(),
                requirement.qualityRequirement(),
                requirement.acquisitionCostHint(),
                requirement.latencyHint(),
                requirement.authorityRequirement(),
                requirement.impactIfUnknown());
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
