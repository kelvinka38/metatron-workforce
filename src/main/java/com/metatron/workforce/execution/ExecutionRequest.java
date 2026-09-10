package com.metatron.workforce.execution;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.time.Instant;
import java.util.List;

/**
 * Canonical execution-admission request.
 *
 * <p>Execution identity, Assignment and Authorization are not sufficient to execute. Consequential
 * Work additionally carries exact authority/derivation/approved-plan references. Legacy constructors
 * remain source-compatible but are intentionally unbound and therefore fail closed for MUTATING Work.</p>
 */
public record ExecutionRequest(
        String executionId,
        Assignment assignment,
        Authorization authorization,
        ExecutionWorkSpec workSpec,
        List<GuidanceReceipt> guidanceReceipts,
        String authoritySnapshotId,
        String derivationReceiptId,
        String planId,
        int planVersion,
        Instant createdAt
) {
    public ExecutionRequest {
        guidanceReceipts = guidanceReceipts == null ? List.of() : List.copyOf(guidanceReceipts);
        authoritySnapshotId = clean(authoritySnapshotId);
        derivationReceiptId = clean(derivationReceiptId);
        planId = clean(planId);
        if (planVersion < 0) throw new IllegalArgumentException("planVersion must not be negative");
    }

    /** Compatibility shape: valid for read-only Work; mutating Work is LEGACY_UNBOUND/fail-closed. */
    public ExecutionRequest(String executionId, Assignment assignment, Authorization authorization,
                            ExecutionWorkSpec workSpec, List<GuidanceReceipt> guidanceReceipts, Instant createdAt) {
        this(executionId, assignment, authorization, workSpec, guidanceReceipts, "", "", "", 0, createdAt);
    }

    /** Compatibility shape: valid for read-only Work; mutating Work is LEGACY_UNBOUND/fail-closed. */
    public ExecutionRequest(String executionId, Assignment assignment, Authorization authorization,
                            ExecutionWorkSpec workSpec, Instant createdAt) {
        this(executionId, assignment, authorization, workSpec, List.of(), "", "", "", 0, createdAt);
    }

    public ExecutionRequest withGovernance(String snapshotId, String receiptId, String approvedPlanId, int approvedPlanVersion) {
        return new ExecutionRequest(executionId, assignment, authorization, workSpec, guidanceReceipts,
                snapshotId, receiptId, approvedPlanId, approvedPlanVersion, createdAt);
    }

    public boolean governanceBound() {
        return !authoritySnapshotId.isBlank() && !derivationReceiptId.isBlank() && !planId.isBlank() && planVersion > 0;
    }

    /** @deprecated canonical execution must provide actual Work. */
    @Deprecated
    public ExecutionRequest(String executionId, Assignment assignment, Authorization authorization,
                            List<GuidanceReceipt> guidanceReceipts, Instant createdAt) {
        this(executionId, assignment, authorization, null, guidanceReceipts, "", "", "", 0, createdAt);
    }

    /** @deprecated canonical execution must provide actual Work. */
    @Deprecated
    public ExecutionRequest(String executionId, Assignment assignment, Authorization authorization, Instant createdAt) {
        this(executionId, assignment, authorization, null, List.of(), "", "", "", 0, createdAt);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
