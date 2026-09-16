package com.metatron.workforce.core;

/**
 * Authoritative completion-evidence check consulted by {@link WorkforceCoreService#transitionAssignment}
 * immediately before allowing a transition to {@code AssignmentStatus.COMPLETED} for any Assignment whose
 * {@link CompletionPolicy} is not {@code EXECUTION_REQUIRED}.
 *
 * This is deliberately the single point every known transition path is routed through: normal execution
 * success, {@code GovernedAutonomousExecutionCapability.reconcileTerminalExecutionCapacity()}, and the
 * {@code WorkforceCoreController} HTTP status-transition endpoint all call
 * {@code WorkforceCoreService.transitionAssignment()} and nothing else in this codebase constructs a
 * COMPLETED Assignment. Wiring the check here, rather than in any individual caller, is what makes it
 * unbypassable by construction rather than by convention.
 */
public interface CompletionEvidenceGate {
    boolean satisfiesCompletion(WorkforceCoreService.Assignment assignment, CompletionPolicy policy);

    /**
     * Safe default: EXECUTION_REQUIRED always satisfies (preserves today's behavior exactly), and any
     * stronger policy is denied until a real evidence-backed gate (see
     * {@code com.metatron.workforce.release.ReleaseEvidenceCompletionGate}) is explicitly wired in. This
     * fails closed rather than open: an Assignment created with PR_REQUIRED or PRODUCTION_REQUIRED but no
     * gate configured can never reach COMPLETED by accident.
     */
    CompletionEvidenceGate DENY_NON_EXECUTION = (assignment, policy) -> policy == CompletionPolicy.EXECUTION_REQUIRED;
}
