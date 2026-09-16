package com.metatron.workforce.release;

import com.metatron.workforce.core.CompletionEvidenceGate;
import com.metatron.workforce.core.CompletionPolicy;
import com.metatron.workforce.core.WorkforceCoreService;

/**
 * Authoritative {@link CompletionEvidenceGate} backed by {@link ReleaseEvidenceStore}. Wire this into
 * {@code WorkforceCoreService}'s constructor so that {@code transitionAssignment(..., COMPLETED)} is the
 * single, unbypassable chokepoint enforcing PR_REQUIRED and PRODUCTION_REQUIRED completion policies for
 * every known caller -- normal execution success, {@code reconcileTerminalExecutionCapacity()}, and the
 * {@code WorkforceCoreController} HTTP endpoint all call {@code transitionAssignment()} and nothing else
 * in this codebase can set {@code AssignmentStatus.COMPLETED}.
 */
public class ReleaseEvidenceCompletionGate implements CompletionEvidenceGate {
    private final ReleaseEvidenceStore evidence;

    public ReleaseEvidenceCompletionGate(ReleaseEvidenceStore evidence) { this.evidence = evidence; }

    @Override
    public boolean satisfiesCompletion(WorkforceCoreService.Assignment assignment, CompletionPolicy policy) {
        if (policy == CompletionPolicy.EXECUTION_REQUIRED) return true;
        ReleaseEvidence record = evidence.get(assignment.assignmentId());
        if (record == null) return false;
        return switch (policy) {
            case EXECUTION_REQUIRED -> true;
            case PR_REQUIRED -> record.satisfiesPrRequired();
            case PRODUCTION_REQUIRED -> record.satisfiesProductionRequired();
        };
    }
}
