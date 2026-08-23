package com.metatron.workforce.execution;

public final class ExecutionPolicyService {

    public boolean allowed(
            ExecutionPolicy policy,
            ExecutionState state
    ) {

        if (state == ExecutionState.RUNNING) {
            return policy.evidenceRequired()
                    && policy.auditRequired();
        }

        return true;
    }
}
