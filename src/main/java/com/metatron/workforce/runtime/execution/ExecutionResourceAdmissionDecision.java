package com.metatron.workforce.runtime.execution;

import java.time.Instant;
import java.util.Objects;

public record ExecutionResourceAdmissionDecision(
        String requestId,
        String attemptId,
        Status status,
        String reason,
        ResourceGrant grant,
        String executorRef,
        String workspaceRef,
        Instant decidedAt) {
    public enum Status { ADMITTED, WAITING_RESOURCES, BLOCKED }
    public ExecutionResourceAdmissionDecision {
        if(requestId==null||requestId.isBlank()||attemptId==null||attemptId.isBlank()) throw new IllegalArgumentException("request/attempt required");
        Objects.requireNonNull(status); reason=reason==null?"":reason; executorRef=executorRef==null?"":executorRef; workspaceRef=workspaceRef==null?"":workspaceRef; Objects.requireNonNull(decidedAt);
        if(status==Status.ADMITTED&&grant==null) throw new IllegalArgumentException("admitted decision requires grant");
    }
}
