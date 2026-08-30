package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

import java.util.List;
import java.util.Objects;

/** Frontier-model-produced semantic normalization of one Human utterance, optionally enriched by downstream planning. */
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
        CaseContinuity caseContinuity,
        String directResponse) {

    /** Backward-compatible full constructor for callers predating bounded Case continuity. */
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
            List<ExecutionWorkSpec> executionWorkPlan,
            boolean freshExternalDataRequired,
            LlmProvider explicitlyRequestedProvider,
            LlmProvider semanticProvider,
            String directResponse) {
        this(objective, target, constraints, requestedDepth, requestedOutput, explicitAssumptions,
                explicitProhibitions, temporalContext, unresolvedSemanticAmbiguity, mode, collaborationMode,
                analyticalProtocols, deterministicCapability, deterministicComputations, executionWorkPlan,
                freshExternalDataRequired, explicitlyRequestedProvider, semanticProvider,
                CaseContinuity.CONTINUE, directResponse);
    }

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
                freshExternalDataRequired, explicitlyRequestedProvider, semanticProvider,
                CaseContinuity.CONTINUE, directResponse);
    }

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
                explicitlyRequestedProvider, semanticProvider, CaseContinuity.CONTINUE, directResponse);
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
        Objects.requireNonNull(semanticProvider, "semanticProvider");
        Objects.requireNonNull(caseContinuity, "caseContinuity");
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

    public NormalizedRequest withRequestedDepth(IntelligenceDepth depth) {
        return new NormalizedRequest(objective, target, constraints, Objects.requireNonNull(depth, "depth"),
                requestedOutput, explicitAssumptions, explicitProhibitions, temporalContext,
                unresolvedSemanticAmbiguity, mode, collaborationMode, analyticalProtocols,
                deterministicCapability, deterministicComputations, executionWorkPlan, freshExternalDataRequired,
                explicitlyRequestedProvider, semanticProvider, caseContinuity, directResponse);
    }

    public NormalizedRequest withExecutionWorkPlan(List<ExecutionWorkSpec> plan) {
        if (mode != IntelligenceMode.EXECUTION) {
            throw new IllegalStateException("execution plan can only be attached to EXECUTION mode");
        }
        return new NormalizedRequest(objective, target, constraints, requestedDepth, requestedOutput,
                explicitAssumptions, explicitProhibitions, temporalContext, unresolvedSemanticAmbiguity,
                mode, collaborationMode, analyticalProtocols, deterministicCapability,
                deterministicComputations, Objects.requireNonNull(plan, "plan"), freshExternalDataRequired,
                explicitlyRequestedProvider, semanticProvider, caseContinuity, directResponse);
    }
}
