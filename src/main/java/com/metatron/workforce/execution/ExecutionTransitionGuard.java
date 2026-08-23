package com.metatron.workforce.execution;

public final class ExecutionTransitionGuard {

    public boolean allowed(
            ExecutionState from,
            ExecutionState to
    ) {

        return switch (from) {

            case REQUESTED ->
                    to == ExecutionState.ADMITTED;

            case ADMITTED ->
                    to == ExecutionState.RUNNING;

            case RUNNING ->
                    to == ExecutionState.COMPLETED
                    || to == ExecutionState.FAILED
                    || to == ExecutionState.TERMINATED;

            case COMPLETED, FAILED, TERMINATED ->
                    false;
        };
    }
}
