package com.metatron.workforce.core;

/**
 * Thrown by WorkforceCoreService.transitionAssignment() specifically when a transition to COMPLETED is
 * denied by the CompletionEvidenceGate (as opposed to any other IllegalStateException the method can
 * throw, e.g. "terminal assignment cannot transition"). Callers that attempt an opportunistic completion
 * after successful execution -- see GovernedAutonomousExecutionCapability -- catch specifically this type
 * so that "evidence not ready yet" is treated as "the Assignment correctly remains ACTIVE," not as an
 * execution failure.
 */
public class CompletionEvidenceRequiredException extends IllegalStateException {
    public CompletionEvidenceRequiredException(String message) { super(message); }
}
