package com.metatron.workforce.interaction.intelligence;

/**
 * How multiple intelligence providers collaborate when a single model is insufficient.
 *
 * CONSENSUS is retained for source compatibility; it means parallel independent proposals
 * followed by synthesis. It must never be interpreted as majority-vote truth.
 */
public enum CollaborationMode {
    SINGLE,
    CONSENSUS,
    LEAD_REVIEW,
    INDEPENDENT_SECOND_OPINION,
    ADVERSARIAL_REVIEW
}
