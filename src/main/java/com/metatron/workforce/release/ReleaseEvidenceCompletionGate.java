package com.metatron.workforce.release;

import com.metatron.workforce.core.CompletionEvidenceGate;
import com.metatron.workforce.core.CompletionPolicy;
import com.metatron.workforce.core.WorkforceCoreService;

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
