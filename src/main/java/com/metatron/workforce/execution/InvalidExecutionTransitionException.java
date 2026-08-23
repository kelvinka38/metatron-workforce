package com.metatron.workforce.execution;

public final class InvalidExecutionTransitionException
        extends RuntimeException {

    public InvalidExecutionTransitionException(
            String message
    ) {
        super(message);
    }
}
