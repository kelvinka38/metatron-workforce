package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

import java.util.List;
import java.util.Objects;

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
        String directResponse,
        com.metatron.workforce.core.CompletionPolicy completionPolicy) {

    public NormalizedRequest(
            String objective, String target, List<String> constraints, IntelligenceDepth requestedDepth,
            String requestedOutput, List<String> explicitAssumptions, List<String> explicitProhibitions,
            String temporalContext, String unresolvedSemanticAmbiguity, IntelligenceMode mode,
            CollaborationMode collaborationMode, List<AnalyticalProtocolType> analyticalProtocols,
            DeterministicCapability deterministicCapability, List<DeterministicComputationSpec> deterministicComputations,
            List<ExecutionWorkSpec> executionWorkPlan, boolean freshExternalDataRequired,
            LlmProvider explicitlyRequestedProvider, LlmProvider semanticProvider, String directResponse) {
        this(objective, target, constraints, requestedDepth, requestedOutput, explicitAssumptions,
                explicitProhibitions, temporalContext, unresolvedSemanticAmbiguity, mode, collaborationMode,
                analyticalProtocols, deterministicCapability, deterministicComputations, executionWorkPlan,
                freshExternalDataRequired, explicitlyRequestedProvider, semanticProvider,
                CaseContinuity.CONTINUE, directResponse, com.metatron.workforce.core.CompletionPolicy.EXECUTION_REQUIRED);
    }

    /** Backward-compatible shape for existing callers that pass an explicit CaseContinuity but predate completionPolicy. */
    public NormalizedRequest(
            String objective, String target, List<String> constraints, IntelligenceDepth requestedDepth,
            String requestedOutput, List<String> explicitAssumptions, List<String> explicitProhibitions,
            String temporalContext, String unresolvedSemanticAmbiguity, IntelligenceMode mode,
            CollaborationMode collaborationMode, List<AnalyticalProtocolType> analyticalProtocols,
            DeterministicCapability deterministicCapability, List<DeterministicComputationSpec> deterministicComputations,
            List<ExecutionWorkSpec> executionWorkPlan, boolean freshExternalDataRequired,
            LlmProvider explicitlyRequestedProvider, LlmProvider semanticProvider,
            CaseContinuity caseContinuity, String directResponse) {
        this(objective, target, constraints, requestedDepth, requestedOutput, explicitAssumptions,
                explicitProhibitions, temporalContext, unresolvedSemanticAmbiguity, mode, collaborationMode,
                analyticalProtocols, deterministicCapability, deterministicComputations, executionWorkPlan,
                freshExternalDataRequired, explicitlyRequestedProvider, semanticProvider,
                caseContinuity, directResponse, com.metatron.workforce.core.CompletionPolicy.EXECUTION_REQUIRED);
    }

    public NormalizedRequest(
            String objective, String target, List<String> constraints, IntelligenceDepth requestedDepth,
            String requestedOutput, List<String> explicitAssumptions, List<String> explicitProhibitions,
            String temporalContext, String unresolvedSemanticAmbiguity, IntelligenceMode mode,
            CollaborationMode collaborationMode, List<AnalyticalProtocolType> analyticalProtocols,
            DeterministicCapability deterministicCapability, List<DeterministicComputationSpec> deterministicComputations,
            boolean freshExternalDataRequired, LlmProvider explicitlyRequestedProvider,
            LlmProvider semanticProvider, String directResponse) {
        this(objective, target, constraints, requestedDepth, requestedOutput, explicitAssumptions,
                explicitProhibitions, temporalContext, unresolvedSemanticAmbiguity, mode, collaborationMode,
                analyticalProtocols, deterministicCapability, deterministicComputations, List.of(),
                freshExternalDataRequired, explicitlyRequestedProvider, semanticProvider,
                CaseContinuity.CONTINUE, directResponse, com.metatron.workforce.core.CompletionPolicy.EXECUTION_REQUIRED);
    }

    public NormalizedRequest(
            String objective, String target, List<String> constraints, IntelligenceDepth requestedDepth,
            String requestedOutput, List<String> explicitAssumptions, List<String> explicitProhibitions,
            String temporalContext, String unresolvedSemanticAmbiguity, IntelligenceMode mode,
            CollaborationMode collaborationMode, List<AnalyticalProtocolType> analyticalProtocols,
            DeterministicCapability deterministicCapability, boolean freshExternalDataRequired,
            LlmProvider explicitlyRequestedProvider, LlmProvider semanticProvider, String directResponse) {
        this(objective, target, constraints, requestedDepth, requestedOutput, explicitAssumptions,
                explicitProhibitions, temporalContext, unresolvedSemanticAmbiguity, mode, collaborationMode,
                analyticalProtocols, deterministicCapability, List.of(), List.of(), freshExternalDataRequired,
                explicitlyRequestedProvider, semanticProvider, CaseContinuity.CONTINUE, directResponse,
                com.metatron.workforce.core.CompletionPolicy.EXECUTION_REQUIRED);
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
        Objects.requireNonNull(caseContinuity, "caseContinuity");
        Objects.requireNonNull(directResponse, "directResponse");
        com.metatron.workforce.core.CompletionPolicy effectiveCompletionPolicy =
                completionPolicy == null ? com.metatron.workforce.core.CompletionPolicy.EXECUTION_REQUIRED : completionPolicy;
        completionPolicy = effectiveCompletionPolicy;
        constraints = List.copyOf(constraints);
        explicitAssumptions = List.copyOf(explicitAssumptions);
        explicitProhibitions = List.copyOf(explicitProhibitions);
        analyticalProtocols = List.copyOf(analyticalProtocols);
        deterministicComputations = List.copyOf(deterministicComputations);
        // Authoritative ceiling, but scoped to the single deterministic release-owner step (the last step
        // in plan order) rather than every step: ReleaseEvidenceStore is keyed per-Assignment, and a
        // multi-step PRODUCTION_REQUIRED/PR_REQUIRED Objective must not require every ordinary
        // prerequisite/build/test/audit step to independently carry its own release-evidence chain. Only
        // the terminal step -- the one that actually owns publishing the PR / triggering the release --
        // is raised to the Objective's floor; earlier steps keep whatever policy they declared (normally
        // EXECUTION_REQUIRED), so they complete on their own ordinary execution evidence.
        List<ExecutionWorkSpec> normalizedPlan = List.copyOf(executionWorkPlan);
        if (!normalizedPlan.isEmpty()) {
            int lastIndex = normalizedPlan.size() - 1;
            ExecutionWorkSpec releaseOwner = ceiling(normalizedPlan.get(lastIndex), effectiveCompletionPolicy);
            List<ExecutionWorkSpec> raised = new java.util.ArrayList<>(normalizedPlan);
            raised.set(lastIndex, releaseOwner);
            normalizedPlan = List.copyOf(raised);
        }
        executionWorkPlan = normalizedPlan;
        if (objective.isBlank()) throw new IllegalArgumentException("objective must not be blank");
        if (mode != IntelligenceMode.EXECUTION && !executionWorkPlan.isEmpty()) {
            throw new IllegalArgumentException("executionWorkPlan is only valid for EXECUTION mode");
        }
    }

    private static ExecutionWorkSpec ceiling(ExecutionWorkSpec step, com.metatron.workforce.core.CompletionPolicy floor) {
        if (step.completionPolicy().ordinal() >= floor.ordinal()) return step;
        return new ExecutionWorkSpec(step.stepId(), step.objective(), step.target(), step.requiredCapability(),
                step.dependsOn(), step.consequence(), step.acceptanceCriteria(), step.evidenceRequirements(), floor);
    }

    public boolean materiallyAmbiguous() {
        return !unresolvedSemanticAmbiguity.isBlank();
    }

    public boolean canReturnFastDirectly() {
        return canReturnPrimaryDirectly();
    }

    public boolean canReturnPrimaryDirectly() {
        return (mode == IntelligenceMode.CASUAL
                    || mode == IntelligenceMode.DISCUSSION
                    || mode == IntelligenceMode.REASONING)
                && collaborationMode == CollaborationMode.SINGLE
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
                explicitlyRequestedProvider, semanticProvider, caseContinuity, directResponse, completionPolicy);
    }

    public NormalizedRequest withExecutionWorkPlan(List<ExecutionWorkSpec> plan) {
        if (mode != IntelligenceMode.EXECUTION) {
            throw new IllegalStateException("execution plan can only be attached to EXECUTION mode");
        }
        return new NormalizedRequest(objective, target, constraints, requestedDepth, requestedOutput,
                explicitAssumptions, explicitProhibitions, temporalContext, unresolvedSemanticAmbiguity,
                mode, collaborationMode, analyticalProtocols, deterministicCapability,
                deterministicComputations, Objects.requireNonNull(plan, "plan"), freshExternalDataRequired,
                explicitlyRequestedProvider, semanticProvider, caseContinuity, directResponse, completionPolicy);
    }

    public NormalizedRequest withCompletionPolicy(com.metatron.workforce.core.CompletionPolicy policy) {
        return new NormalizedRequest(objective, target, constraints, requestedDepth, requestedOutput,
                explicitAssumptions, explicitProhibitions, temporalContext, unresolvedSemanticAmbiguity,
                mode, collaborationMode, analyticalProtocols, deterministicCapability,
                deterministicComputations, executionWorkPlan, freshExternalDataRequired,
                explicitlyRequestedProvider, semanticProvider, caseContinuity, directResponse,
                Objects.requireNonNull(policy, "policy"));
    }
}
