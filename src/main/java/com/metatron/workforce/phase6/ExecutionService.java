package com.metatron.workforce.phase6;

import com.metatron.workforce.phase5.ExecutionFeasibility;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class ExecutionService {
    @FunctionalInterface
    public interface Executor { ExecutionResult execute(WorkProposal proposal); }

    public ExecutionRecord execute(WorkProposal proposal, ApprovalDecision approval, AuthorizationRequest request,
                                   AuthorizationService authorization, ExecutionFeasibility feasibility,
                                   Executor executor, String executionId, Instant startedAt, Instant completedAt,
                                   String retryOfExecutionId) {
        Objects.requireNonNull(feasibility, "feasibility");
        var decision = authorization.authorize(proposal, approval, request);
        if (!decision.allowed()) throw new SecurityException(decision.reason());
        if (feasibility.status() == ExecutionFeasibility.Status.BLOCKED) {
            return new ExecutionRecord(executionId, proposal.proposalId(), proposal.actorId(), proposal.action(), startedAt, completedAt,
                    proposal.contextId(), decision.authorizationReference(), List.of(), List.of(), ExecutionRecord.Status.BLOCKED,
                    String.join(",", feasibility.blockingReasons()), retryOfExecutionId);
        }
        if (feasibility.status() == ExecutionFeasibility.Status.PARTIAL) {
            return new ExecutionRecord(executionId, proposal.proposalId(), proposal.actorId(), proposal.action(), startedAt, completedAt,
                    proposal.contextId(), decision.authorizationReference(), List.of(), List.of(), ExecutionRecord.Status.PARTIAL,
                    "execution-feasibility-partial", retryOfExecutionId);
        }
        return executeAfterAuthorization(proposal, decision, executor, executionId, startedAt, completedAt, retryOfExecutionId);
    }

    public ExecutionRecord executeAuthorized(WorkProposal proposal, ApprovalDecision approval, AuthorizationRequest request,
                                             AuthorizationService authorization, Executor executor, String executionId,
                                             Instant startedAt, Instant completedAt, String retryOfExecutionId) {
        var decision = authorization.authorize(proposal, approval, request);
        if (!decision.allowed()) throw new SecurityException(decision.reason());
        return executeAfterAuthorization(proposal, decision, executor, executionId, startedAt, completedAt, retryOfExecutionId);
    }

    /** Historical lifecycle adapter retained for cross-phase acceptance evidence. */
    @Deprecated
    public Execution validating(Execution execution) {
        requireState(execution, Execution.State.REQUESTED);
        return copy(execution, Execution.State.VALIDATING, null, null, null, null, null);
    }

    @Deprecated
    public Execution admit(Execution execution, AuthorizationDecision authorization, Instant at) {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(at, "at");
        requireState(execution, Execution.State.VALIDATING);
        if (!authorization.requestId().equals(execution.requestId())) {
            throw new IllegalStateException("authorization does not match execution request");
        }
        if (!authorization.usableAt(at)) {
            throw new IllegalStateException("authorization is not usable at execution admission time");
        }
        return copy(execution, Execution.State.AUTHORIZED, null, null, at, null, null);
    }

    @Deprecated
    public Execution start(Execution execution, Instant at, Predicate<Execution> executable) {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(executable, "executable");
        requireState(execution, Execution.State.AUTHORIZED);
        if (!executable.test(execution)) {
            return copy(execution, Execution.State.BLOCKED, null, "execution blocked", at, null, at);
        }
        return copy(execution, Execution.State.RUNNING, at, null, at, null, null);
    }

    @Deprecated
    public Execution fail(Execution execution, Instant at, String reason) {
        Objects.requireNonNull(at, "at");
        requireText(reason, "reason");
        requireState(execution, Execution.State.RUNNING);
        return copy(execution, Execution.State.FAILED, execution.startedAt(), null, execution.terminalAt(), reason, at);
    }

    @Deprecated
    public Execution complete(Execution execution, Instant at, String result) {
        Objects.requireNonNull(at, "at");
        requireText(result, "result");
        if (execution.terminal()) throw new IllegalStateException("execution is already terminal");
        requireState(execution, Execution.State.RUNNING);
        return copy(execution, Execution.State.COMPLETED, execution.startedAt(), result, execution.terminalAt(), null, at);
    }

    private Execution copy(Execution e, Execution.State state, Instant startedAt, String result,
                           Instant terminalAt, String failureReason, Instant terminalOverride) {
        Instant terminal = terminalOverride != null ? terminalOverride : terminalAt;
        return new Execution(e.executionId(), e.requestId(), e.workerId(), e.assignmentId(), e.authorizationId(),
                state, e.requestedAt(), startedAt, state == Execution.State.COMPLETED ? terminal : null,
                terminal, result, failureReason, e.evidenceReference());
    }

    private static void requireState(Execution execution, Execution.State expected) {
        Objects.requireNonNull(execution, "execution");
        if (execution.state() != expected) throw new IllegalStateException("expected state " + expected + " but was " + execution.state());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    private ExecutionRecord executeAfterAuthorization(WorkProposal proposal, AuthorizationService.AuthorizationResult decision,
                                                      Executor executor, String executionId, Instant startedAt, Instant completedAt,
                                                      String retryOfExecutionId) {
        Objects.requireNonNull(executor, "executor");
        var result = Objects.requireNonNull(executor.execute(proposal), "executor result");
        return new ExecutionRecord(executionId, proposal.proposalId(), proposal.actorId(), proposal.action(), startedAt, completedAt,
                proposal.contextId(), decision.authorizationReference(), result.inputs(), result.outputs(),
                result.failed() ? ExecutionRecord.Status.FAILED : ExecutionRecord.Status.SUCCEEDED,
                result.failureReason(), retryOfExecutionId);
    }

    public AuthorizationService.AuthorizationResult revalidate(WorkProposal proposal, ApprovalDecision approval, AuthorizationRequest request,
                                                               AuthorizationService authorization) {
        return authorization.authorize(proposal, approval, request);
    }

    public record ExecutionResult(List<String> inputs, List<String> outputs, boolean failed, String failureReason) {
        public ExecutionResult {
            Objects.requireNonNull(inputs, "inputs"); Objects.requireNonNull(outputs, "outputs");
            if (failed && (failureReason == null || failureReason.isBlank())) throw new IllegalArgumentException("failed result requires failureReason");
        }
        public static ExecutionResult success(List<String> inputs, List<String> outputs) { return new ExecutionResult(inputs, outputs, false, null); }
        public static ExecutionResult failure(List<String> inputs, String reason) { return new ExecutionResult(inputs, List.of(), true, reason); }
    }
}
