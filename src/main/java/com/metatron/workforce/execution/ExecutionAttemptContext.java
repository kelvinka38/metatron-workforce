package com.metatron.workforce.execution;

import java.util.Optional;

/**
 * Transitional compatibility context while ObjectiveWorkspaceService callers migrate to explicit
 * attempt-bound APIs. It is thread-scoped and carries identity only; authority still comes from
 * ExecutionAttemptService/ExecutionGate.
 */
public final class ExecutionAttemptContext {
    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();
    private ExecutionAttemptContext() {}

    public static void bind(ExecutionAttempt attempt) {
        if (attempt == null) throw new IllegalArgumentException("attempt required");
        CURRENT.set(new Binding(attempt.attemptId(), attempt.fencingToken(), attempt.objectiveId(), attempt.stepId(), attempt.workerId()));
    }

    public static Optional<Binding> current() { return Optional.ofNullable(CURRENT.get()); }

    public static void clearIf(String attemptId) {
        Binding current=CURRENT.get();
        if(current!=null&&current.attemptId().equals(attemptId)) CURRENT.remove();
    }

    public static void clear(){CURRENT.remove();}

    public record Binding(String attemptId,long fencingToken,String objectiveId,String stepId,String workerId) {}
}
