package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

import java.util.List;
import java.util.Objects;

/**
 * Semantic normalization of one Human utterance.
 * semanticProvider is null only when the normalization was produced deterministically without an LLM.
 */
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
        List<ExecutionWorkSpec> executionWorkPlan,
        boolean freshExternalDataRequired,
        LlmProvider explicitlyRequestedProvider,
        LlmProvider semanticProvider,
        String directResponse) {

    /** Backward-compatible constructor for callers with arithmetic but no execution work plan. */
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
            List<DeterministicComputationSpec> deterministicComputations,
            boolean freshExternalDataRequired,
            LlmProvider explicitlyRequestedProvider,
            LlmProvider semanticProvider,
            String directResponse) {
        this(objective, target, constraints, requestedDepth, requestedOutput, explicitAssumptions,
                explicitProhibitions, temporalContext, unresolvedSemanticAmbiguity, mode, collaborationMode,
                analyticalProtocols, deterministicCapability, deterministicComputations, List.of(),
                freshExternalDataRequired, explicitlyRequestedProvider, semanticProvider, directResponse);
    }

    /** Backward-compatible constructor for callers with no arithmetic or execution work plan. */
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
                analyticalProtocols, deterministicCapability, List.of(), List.of(), freshExternalDataRequired,
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
        Objects.requireNonNull(executionWorkPlan, "executionWorkPlan");
        Objects.requireNonNull(directResponse, "directResponse");
        constraints = List.copyOf(constraints);
        explicitAssumptions = List.copyOf(explicitAssumptions);
        explicitProhibitions = List.copyOf(explicitProhibitions);
        analyticalProtocols = List.copyOf(analyticalProtocols);
        deterministicComputations = List.copyOf(deterministicComputations);
        executionWorkPlan = List.copyOf(executionWorkPlan);
        if (objective.isBlank()) throw new IllegalArgumentException("objective must not be blank");
        if (mode != IntelligenceMode.EXECUTION && !executionWorkPlan.isEmpty()) {
            throw new IllegalArgumentException("executionWorkPlan is only valid for EXECUTION mode");
        }
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
                && executionWorkPlan.isEmpty()
                && !freshExternalDataRequired
                && !directResponse.isBlank();
    }

    public boolean deterministicallyNormalized() {
        return semanticProvider == null;
    }

    /** Applies a Human-selected depth without changing any other semantic interpretation or work plan. */
    public NormalizedRequest withRequestedDepth(IntelligenceDepth depth) {
        return new NormalizedRequest(objective, target, constraints, Objects.requireNonNull(depth, "depth"),
                requestedOutput, explicitAssumptions, explicitProhibitions, temporalContext,
                unresolvedSemanticAmbiguity, mode, collaborationMode, analyticalProtocols,
                deterministicCapability, deterministicComputations, executionWorkPlan, freshExternalDataRequired,
                explicitlyRequestedProvider, semanticProvider, directResponse);
    }
}
