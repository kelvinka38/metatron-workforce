package com.metatron.workforce.interaction.intelligence;

import java.util.Objects;

/** Applies the Human depth contract after semantic normalization and before Case/planning. */
public final class IntelligenceDepthApplication {
    private IntelligenceDepthApplication() {}

    public static NormalizedRequest apply(NormalizedRequest semanticRequest, IntelligenceDepthContract contract) {
        Objects.requireNonNull(semanticRequest, "semanticRequest");
        Objects.requireNonNull(contract, "contract");
        IntelligenceDepth effective = contract.applyTo(semanticRequest.requestedDepth());
        return effective == semanticRequest.requestedDepth()
                ? semanticRequest
                : semanticRequest.withRequestedDepth(effective);
    }
}
