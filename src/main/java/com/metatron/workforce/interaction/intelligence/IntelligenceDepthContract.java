package com.metatron.workforce.interaction.intelligence;

import java.util.Objects;

/**
 * Human-requested intelligence depth contract.
 *
 * The Human may explicitly select FAST / ANALYZE / DEEP. When no explicit depth is
 * selected, frontier semantic interpretation may infer the appropriate depth from the
 * natural-language request. This contract controls reasoning resources only; it never
 * creates authority or changes institutional consequence.
 */
public record IntelligenceDepthContract(IntelligenceDepth selectedDepth) {

    public static IntelligenceDepthContract automatic() {
        return new IntelligenceDepthContract(null);
    }

    public static IntelligenceDepthContract selected(IntelligenceDepth depth) {
        return new IntelligenceDepthContract(Objects.requireNonNull(depth, "depth"));
    }

    public boolean explicitlySelected() {
        return selectedDepth != null;
    }

    public IntelligenceDepth applyTo(IntelligenceDepth semanticallyInferredDepth) {
        Objects.requireNonNull(semanticallyInferredDepth, "semanticallyInferredDepth");
        return explicitlySelected() ? selectedDepth : semanticallyInferredDepth;
    }
}
