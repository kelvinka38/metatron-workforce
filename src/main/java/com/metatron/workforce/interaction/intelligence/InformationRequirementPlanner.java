package com.metatron.workforce.interaction.intelligence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
                String key = requirement.toLowerCase(Locale.ROOT);
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
            // The external information requirement must preserve the frontier-normalized subject of the
            // Human request. A generic label such as "current external evidence" becomes the literal web
            // search query downstream and can retrieve unrelated material (for example, electrical current)
            // even when the normalized objective is a gold-price, FX, or weather question.
            String currentQuestion = request.objective().trim();
            if (currentQuestion.isBlank()) currentQuestion = request.requestedOutput().trim();
            if (currentQuestion.isBlank()) currentQuestion = request.target().trim();
            if (currentQuestion.isBlank()) {
                throw new IllegalArgumentException("fresh external data requires a non-blank semantic query");
            }
            String key = "fresh:" + currentQuestion.toLowerCase(Locale.ROOT);
            deduplicated.putIfAbsent(key, new InformationRequirement(
                    "ir-" + sequence,
                    currentQuestion,
                    "the normalized objective depends on current external reality",
                    InformationRequirementStatus.MISSING,
                    List.of("authorized structured external source", "web/external research"),
                    List.of(), "current", "source-attributed and current", "bounded", "interactive",
                    "authorized read access", "answer may be stale or unsafe without current evidence"));
        }
        return new ArrayList<>(deduplicated.values());
    }

    private static List<String> preferredSources(String requirement) {
        String value = requirement.toLowerCase(Locale.ROOT);
        if (value.contains("metric") || value.contains("cash") || value.contains("historical") || value.contains("timeline")) {
            return List.of("validated institutional Knowledge", "connected system/API", "institutional artifact");
        }
        if (value.contains("market") || value.contains("external") || value.contains("competitive")) {
            return List.of("authorized external structured data", "web/external research", "validated Knowledge");
        }
        return List.of("validated institutional Knowledge", "institutional artifact", "connected system/API", "authorized Worker observation");
    }
}
