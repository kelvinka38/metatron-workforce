package com.metatron.workforce.interaction.intelligence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds deterministic minimum information requirements from semantically selected analytical protocols. */
public final class InformationRequirementPlanner {
    private final AnalyticalProtocolRegistry registry;

    public InformationRequirementPlanner(AnalyticalProtocolRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public List<InformationRequirement> plan(NormalizedRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, InformationRequirement> deduplicated = new LinkedHashMap<>();
        int sequence = 1;
        for (AnalyticalProtocol protocol : registry.resolve(request.analyticalProtocols())) {
            for (String requirement : protocol.minimumInformationRequirements()) {
                String key = requirement.toLowerCase(java.util.Locale.ROOT);
                if (deduplicated.containsKey(key)) continue;
                String id = "ir-" + sequence++;
                deduplicated.put(key, new InformationRequirement(
                        id,
                        requirement,
                        "required by " + protocol.type() + " analytical protocol",
                        InformationRequirementStatus.MISSING,
                        preferredSources(requirement),
                        List.of(),
                        request.temporalContext().isBlank() ? "fit for objective" : request.temporalContext(),
                        "sufficient to support a material conclusion",
                        "prefer lower-cost reliable source",
                        request.requestedDepth() == IntelligenceDepth.DEEP ? "extended" : "interactive",
                        "respect source access and institutional authority",
                        "may materially change or limit the conclusion"));
            }
        }
        if (request.freshExternalDataRequired()) {
            // Freshness is a property of the Human's normalized objective, not a searchable subject by itself.
            // Bind the requirement question to that objective so acquisition searches for the actual thing the
            // Human asked about (gold price, exchange rate, incident state, etc.) instead of the meaningless
            // placeholder phrase "current external evidence".
            String freshQuestion = request.objective().trim();
            if (freshQuestion.isBlank()) throw new IllegalArgumentException("fresh external objective must not be blank");
            deduplicated.putIfAbsent("current external evidence", new InformationRequirement(
                    "ir-" + sequence,
                    freshQuestion,
                    "the normalized objective depends on current external reality",
                    InformationRequirementStatus.MISSING,
                    List.of("authorized structured external source", "web/external research"),
                    List.of(), "current", "source-attributed and current", "bounded", "interactive",
                    "authorized read access", "answer may be stale or unsafe without current evidence"));
        }
        return new ArrayList<>(deduplicated.values());
    }

    private static List<String> preferredSources(String requirement) {
        String value = requirement.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("metric") || value.contains("cash") || value.contains("historical") || value.contains("timeline")) {
            return List.of("validated institutional Knowledge", "connected system/API", "institutional artifact");
        }
        if (value.contains("market") || value.contains("external") || value.contains("competitive")) {
            return List.of("authorized external structured data", "web/external research", "validated Knowledge");
        }
        return List.of("validated institutional Knowledge", "institutional artifact", "connected system/API", "authorized Worker observation");
    }
}
