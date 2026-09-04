package com.metatron.workforce.interaction.intelligence;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Degraded semantic bridge for one already-ratified Founder control contract.
 *
 * This is intentionally not a general keyword router. It is consulted only after every configured
 * semantic provider has failed and only recognizes the canonical Founder appointment of the
 * Gateway Director / Head of Gateway. All ordinary Human language still belongs to the frontier
 * semantic boundary.
 */
final class BoundedFounderControlInterpreter {
    static final String FOUNDER_HUMAN_ID = "human-primary";
    static final String GATEWAY_DIRECTOR_ROLE = "ROLE-HEAD-OF-GATEWAY";

    private BoundedFounderControlInterpreter() {}

    static Optional<NormalizedRequest> interpret(String humanId, String humanText) {
        if (!FOUNDER_HUMAN_ID.equals(humanId)) return Optional.empty();
        String semantic = folded(humanText);
        boolean appointmentAction = containsAny(semantic,
                "create", "appoint", "form", "staff", "provision", "set up", "setup");
        boolean gatewayDirectorRole = containsAny(semantic,
                "gateway head", "head of gateway", "gateway director", "role gateway head",
                "role head of gateway");
        boolean workforceIntent = containsAny(semantic,
                "workforce", "worker", "role", "director", "head");
        if (!appointmentAction || !gatewayDirectorRole || !workforceIntent) return Optional.empty();

        return Optional.of(new NormalizedRequest(
                "Create a Workforce Worker and appoint it as the Gateway Director / Head of Gateway",
                GATEWAY_DIRECTOR_ROLE,
                List.of(
                        "Create a persistent institutional Worker through governed Workforce staffing",
                        "Assign the canonical Gateway Director role and position",
                        "Bind the approved usable runtime/tool profile",
                        "Attest only capabilities approved by the Gateway Director formation contract"),
                IntelligenceDepth.ANALYZE,
                "appointment confirmation with Worker, role, capability and runtime evidence",
                List.of(),
                List.of(
                        "Do not bypass Workforce governance",
                        "Do not self-grant authority beyond the approved Gateway Director formation contract"),
                "",
                "",
                IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE,
                List.of(),
                DeterministicCapability.NONE,
                List.of(),
                List.of(),
                false,
                null,
                null,
                CaseContinuity.NEW,
                ""));
    }

    private static boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) return true;
        }
        return false;
    }

    private static String folded(String value) {
        String source = value == null ? "" : value;
        String decomposed = Normalizer.normalize(source, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
