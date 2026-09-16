package com.metatron.workforce.core;

public interface CompletionEvidenceGate {
    boolean satisfiesCompletion(WorkforceCoreService.Assignment assignment, CompletionPolicy policy);

    CompletionEvidenceGate DENY_NON_EXECUTION = (assignment, policy) -> policy == CompletionPolicy.EXECUTION_REQUIRED;
}
