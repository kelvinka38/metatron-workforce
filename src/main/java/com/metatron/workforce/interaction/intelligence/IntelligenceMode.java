package com.metatron.workforce.interaction.intelligence;

/** Governance intensity for an intelligence request. */
public enum IntelligenceMode {
    CASUAL,
    DISCUSSION,
    REASONING,
    DECISION,
    EXECUTION;

    public boolean requiresGovernance() {
        return this.ordinal() >= REASONING.ordinal();
    }
}
