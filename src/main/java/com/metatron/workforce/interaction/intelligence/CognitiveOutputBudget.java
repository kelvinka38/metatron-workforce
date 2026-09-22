package com.metatron.workforce.interaction.intelligence;

/**
 * Bounded, request-aware output-token budget for one cognition call. The Metatron-owned cognition
 * transport previously applied a single global ceiling (COGNITION_MAX_OUTPUT_TOKENS, historically 256)
 * to every request regardless of shape, which was large enough for a short action-selection or
 * reflection JSON object but too small for a request whose JSON response must itself carry generated
 * source/work-product content (for example workspace.file.write's "content" input). Each tier still
 * carries a small, finite, enforced token ceiling; a caller cannot request unbounded generation.
 */
public enum CognitiveOutputBudget {
    /** Action selection and reflection: a short JSON object plus a brief rationale/summary. */
    SELECTION(1_536),
    /** The chosen action itself carries generated source/work-product text in its JSON response. */
    CONTENT_GENERATION(6_144);

    private final int maxOutputTokens;

    CognitiveOutputBudget(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }
}
