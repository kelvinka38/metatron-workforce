package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.phase3.ExecutionHandoffRequest;
import com.metatron.workforce.phase9.BoundaryResult;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Fail-closed admission bridge from an Intelligence Case to Workforce's execution handoff contract.
 *
 * Intelligence does not authorize or execute. This service only creates a Workforce-owned handoff
 * after callers supply externally produced Assignment, Authorization and Gateway evidence.
 */
public final class IntelligenceExecutionAdmissionService {
    private final Clock clock;

    public IntelligenceExecutionAdmissionService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AdmissionResult admit(
            IntelligenceCase intelligenceCase,
            String workerId,
            String assignmentId,
            String workPackageId,
            String executionId,
            String authorizationReference,
            String gatewayReference,
            BoundaryResult authorizationBoundary,
            BoundaryResult gatewayBoundary) {

        Objects.requireNonNull(intelligenceCase, "intelligenceCase");
        requireSuccess(authorizationBoundary, InstitutionalIntelligenceReferenceBridge.AUTHORIZATION_CONTRACT);
        requireSuccess(gatewayBoundary, InstitutionalIntelligenceReferenceBridge.GATEWAY_CONTRACT);
        requireNonBlank(workerId, "workerId");
        requireNonBlank(assignmentId, "assignmentId");
        requireNonBlank(workPackageId, "workPackageId");
        requireNonBlank(executionId, "executionId");
        requireNonBlank(authorizationReference, "authorizationReference");
        requireNonBlank(gatewayReference, "gatewayReference");

        if (!authorizationReference.equals(authorizationBoundary.authorityReference())) {
            throw new SecurityException("authorization reference does not match authoritative boundary result");
        }

        Instant issuedAt = Instant.now(clock);
        ExecutionHandoffRequest handoff = new ExecutionHandoffRequest(
                "handoff:" + executionId,
                workerId,
                assignmentId,
                authorizationReference,
                workPackageId,
                executionId,
                issuedAt);

        IntelligenceCase linked = intelligenceCase.withExternalReferences(
                List.of(
                        "assignment:" + assignmentId,
                        authorizationReference,
                        gatewayReference,
                        "execution-handoff:" + handoff.handoffId()),
                List.of(),
                IntelligenceCaseStatus.WAITING_ON_EXTERNAL_STATE);

        return new AdmissionResult(linked, handoff, gatewayReference);
    }

    private static void requireSuccess(BoundaryResult boundary, String expectedContract) {
        Objects.requireNonNull(boundary, "boundary");
        if (!expectedContract.equals(boundary.contractId())) {
            throw new IllegalArgumentException("boundary contract mismatch: expected " + expectedContract
                    + " but was " + boundary.contractId());
        }
        if (!boundary.succeeded()) {
            throw new SecurityException("execution admission denied by boundary: " + boundary.status());
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    public record AdmissionResult(
            IntelligenceCase intelligenceCase,
            ExecutionHandoffRequest handoff,
            String gatewayReference) {
        public AdmissionResult {
            Objects.requireNonNull(intelligenceCase, "intelligenceCase");
            Objects.requireNonNull(handoff, "handoff");
            Objects.requireNonNull(gatewayReference, "gatewayReference");
        }
    }
}
