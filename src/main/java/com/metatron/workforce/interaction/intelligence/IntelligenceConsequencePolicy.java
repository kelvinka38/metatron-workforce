package com.metatron.workforce.interaction.intelligence;

import java.util.Objects;

/**
 * Separates requested intelligence depth from institutional consequence.
 *
 * FAST / ANALYZE / DEEP are resource-depth choices. They do not manufacture authority or
 * upgrade a reasoning request into a consequential institutional act. Consequence for actual
 * Decision / Execution must come from authoritative institutional context, not this fallback.
 */
public final class IntelligenceConsequencePolicy {
    private IntelligenceConsequencePolicy() {}

    public static String forNonConsequentialMode(IntelligenceMode mode) {
        Objects.requireNonNull(mode, "mode");
        return switch (mode) {
            case CASUAL, DISCUSSION -> "LOW";
            case REASONING -> "MEDIUM";
            case DECISION, EXECUTION -> throw new IllegalArgumentException(
                    "institutional consequence for DECISION/EXECUTION requires authoritative context");
        };
    }
}
