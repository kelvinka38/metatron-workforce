package com.metatron.workforce.phase6;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class ExecutionService {
    @FunctionalInterface
    public interface Executor {
        ExecutionResult execute(WorkProposal proposal);
    }

    public ExecutionRecord execute(WorkProposal proposal, ApprovalDecision approval, AuthorizationRequest request,
                                   AuthorizationService authorization, Executor executor, String executionId,
                                   Instant startedAt, Instant completedAt) {
        Objects.requireNonNull(executor, "executor");
        var decision = authorization.authorize(proposal, approval, request);
        if (!decision.allowed()) throw new SecurityException(decision.reason());
        var result = Objects.requireNonNull(executor.execute(proposal), "executor result");
        return new ExecutionRecord(
                executionId, proposal.proposalId(), proposal.actorId(), proposal.action(), startedAt, completedAt,
                proposal.contextId(), decision.authorizationReference(), result.inputs(), result.outputs(),
                result.failed() ? ExecutionRecord.Status.FAILED : ExecutionRecord.Status.SUCCEEDED,
                result.failureReason());
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
