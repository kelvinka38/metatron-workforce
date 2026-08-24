package com.metatron.workforce.execution;

/** A concrete capability that may be invoked only after execution admission. */
@FunctionalInterface
public interface ExecutionCapability {
    ExecutionResult execute(ExecutionCommand command);
}
