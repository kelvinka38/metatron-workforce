package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

import java.util.List;
import java.util.Objects;

/** Frontier-model-produced semantic normalization of one Human utterance. */
public record NormalizedRequest(
        String objective,
        String target,
        List<String> constraints,
        IntelligenceDepth requestedDepth,
        String requestedOutput,
        List<String> explicitAssumptions,
        List<String> explicitProhibitions,
        String temporalContext,
        String unresolvedSemanticAmbiguity,
        IntelligenceMode mode,
        CollaborationMode collaborationMode,
        List<AnalyticalProtocolType> analyticalProtocols,
        DeterministicCapability deterministicCapability,
        List<DeterministicComputationSpec> deterministicComputations,
        boolean freshExternalDataRequired,
        LlmProvider explicitlyRequestedProvider,
        LlmProvider semanticProvider,
        String directResponse) {

    /** Backward-compatible constructor for callers with no explicit arithmetic computation plan. */
    public NormalizedRequest(
            String objective,
            String target,
            List<String> constraints,
            IntelligenceDepth requestedDepth,
            String requestedOutput,
            List<String> explicitAssumptions,
            List<String> explicitProhibitions,
            String temporalContext,
            String unresolvedSemanticAmbiguity,
            IntelligenceMode mode,
            CollaborationMode collaborationMode,
            List<AnalyticalProtocolType> analyticalProtocols,
            DeterministicCapability deterministicCapability,
            boolean freshExternalDataRequired,
            LlmProvider explicitlyRequestedProvider,
            LlmProvider semanticProvider,
            String directResponse) {
        this(objective, target, constraints, requestedDepth, requestedOutput, explicitAssumptions,
                explicitProhibitions, temporalContext, unresolvedSemanticAmbiguity, mode, collaborationMode,
                analyticalProtocols, deterministicCapability, List.of(), freshExternalDataRequired,
                explicitlyRequestedProvider, semanticProvider, directResponse);
    }

    public NormalizedRequest {
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(constraints, "constraints");
        Objects.requireNonNull(requestedDepth, "requestedDepth");
        Objects.requireNonNull(requestedOutput, "requestedOutput");
        Objects.requireNonNull(explicitAssumptions, "explicitAssumptions");
        Objects.requireNonNull(explicitProhibitions, "explicitProhibitions");
        Objects.requireNonNull(temporalContext, "temporalContext");
        Objects.requireNonNull(unresolvedSemanticAmbiguity, "unresolvedSemanticAmbiguity");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(collaborationMode, "collaborationMode");
        Objects.requireNonNull(analyticalProtocols, "analyticalProtocols");
        Objects.requireNonNull(deterministicCapability, "deterministicCapability");
        Objects.requireNonNull(deterministicComputations, "deterministicComputations");
        Objects.requireNonNull(semanticProvider, "semanticProvider");
        Objects.requireNonNull(directResponse, "directResponse");
        constraints = List.copyOf(constraints);
        explicitAssumptions = List.copyOf(explicitAssumptions);
        explicitProhibitions = List.copyOf(explicitProhibitions);
        analyticalProtocols = List.copyOf(analyticalProtocols);
        deterministicComputations = List.copyOf(deterministicComputations);
        if (objective.isBlank()) throw new IllegalArgumentException("objective must not be blank");
    }

    public boolean materiallyAmbiguous() {
        return !unresolvedSemanticAmbiguity.isBlank();
    }

    public boolean canReturnFastDirectly() {
        return requestedDepth == IntelligenceDepth.FAST
                && mode == IntelligenceMode.DISCUSSION
                && collaborationMode == CollaborationMode.SINGLE
                && analyticalProtocols.isEmpty()
                && deterministicCapability == DeterministicCapability.NONE
                && deterministicComputations.isEmpty()
                && !freshExternalDataRequired
                && !directResponse.isBlank();
    }
}
