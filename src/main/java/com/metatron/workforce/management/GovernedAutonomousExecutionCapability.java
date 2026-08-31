package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.Assignment;
import com.metatron.workforce.execution.Authorization;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.execution.ExecutionRequest;
import com.metatron.workforce.execution.ExecutionState;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Production execution boundary for autonomous capabilities.
 *
 * Effects are forbidden until an eligible Worker has finite capacity reserved, a durable Core
 * Assignment exists, and canonical Execution admission accepts the matching Authorization.
 * Assignment terminalization releases the reservation through WorkforceCoreService.
 */
public final class GovernedAutonomousExecutionCapability implements AutonomousExecutionCapability {
    private static final Duration DEFAULT_CAPACITY_WAIT = Duration.ofSeconds(30);
    private static final long CAPACITY_RETRY_MILLIS = 25L;

    private final AutonomousExecutionCapability delegate;
    private final WorkforceCoreService core;
    private final ExecutionAdmissionService admission;
    private final Clock clock;
    private final Duration capacityWait;

    public GovernedAutonomousExecutionCapability(AutonomousExecutionCapability delegate,
                                                  WorkforceCoreService core,
                                                  ExecutionAdmissionService admission,
                                                  Clock clock) {
        this(delegate, core, admission, clock, DEFAULT_CAPACITY_WAIT);
    }

    GovernedAutonomousExecutionCapability(AutonomousExecutionCapability delegate,
                                          WorkforceCoreService core,
                                          ExecutionAdmissionService admission,
                                          Clock clock,
                                          Duration capacityWait) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.core = Objects.requireNonNull(core, "core");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.capacityWait = Objects.requireNonNull(capacityWait, "capacityWait");
        if (capacityWait.isNegative()) throw new IllegalArgumentException("capacityWait must not be negative");
    }

    @Override public String capabilityRef() { return delegate.capabilityRef(); }
    @Override public String capabilityDescription() { return delegate.capabilityDescription(); }
    @Override public String authorityReference() { return delegate.authorityReference(); }
    @Override public String authorizationReference() { return delegate.authorizationReference(); }
    @Override public double minimumCapabilityLevel() { return delegate.minimumCapabilityLevel(); }
    @Override public double requiredCapacity() { return delegate.requiredCapacity(); }
    @Override public boolean supportsWorker(String workerId) { return delegate.supportsWorker(workerId); }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        String authorityRef = requireReference(delegate.authorityReference(), "authority-reference-missing");
        String authorizationRef = requireReference(delegate.authorizationReference(), "authorization-reference-missing");

        WorkforceCoreService.Worker worker = awaitEligibleWorker(request);
        WorkforceCoreService.Participation participation = core.participations(worker.workerId()).stream()
                .filter(p -> p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE)
                .sorted(Comparator.comparing(WorkforceCoreService.Participation::participationId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("staffing-required:no-active-participation:" + worker.workerId()));

        String key = stableKey(request);
        String reservationId = "capacity-reservation:" + key;
        String assignmentId = "assignment:" + key;
        boolean assignmentCreated = false;
        try {
            core.reserveCapacity(reservationId, assignmentId, request.objectiveId(), worker.workerId(),
                    delegate.requiredCapacity());
            WorkforceCoreService.Assignment coreAssignment = core.assignReserved(
                    reservationId,
                    participation.participationId(),
                    authorityRef,
                    authorizationRef,
                    request.workSpec().description());
            assignmentCreated = true;

            ExecutionState admitted = admission.admit(new ExecutionRequest(
                    "execution:" + key,
                    new Assignment(coreAssignment.assignmentId(), coreAssignment.workerId()),
                    new Authorization(coreAssignment.authorizationRef(), coreAssignment.workerId()),
                    clock.instant()));
            if (admitted != ExecutionState.ADMITTED) {
                throw new SecurityException("execution-not-admitted:" + admitted);
            }

            CapabilityRequest allocated = request.withAllocation(
                    coreAssignment.workerId(), coreAssignment.assignmentId(), coreAssignment.authorizationRef());
            CapabilityResult result = delegate.execute(allocated);
            verifyAttribution(result, coreAssignment);

            core.transitionAssignment(coreAssignment.assignmentId(),
                    result.success() ? WorkforceCoreService.AssignmentStatus.COMPLETED
                            : WorkforceCoreService.AssignmentStatus.CANCELLED);
            return result;
        } catch (RuntimeException failure) {
            if (assignmentCreated) {
                cancelIfActive(assignmentId);
            } else {
                releaseIfActive(reservationId);
            }
            throw failure;
        }
    }

    private WorkforceCoreService.Worker awaitEligibleWorker(CapabilityRequest request) {
        long deadline = System.nanoTime() + capacityWait.toNanos();
        while (true) {
            List<WorkforceCoreService.Worker> eligible = core.eligibleWorkers(
                            capabilityRef(), minimumCapabilityLevel(), requiredCapacity(), clock.instant()).stream()
                    .filter(w -> supportsWorker(w.workerId()))
                    .toList();
            if (!eligible.isEmpty()) return eligible.getFirst();
            if (!hasQualifiedParticipant()) {
                throw new IllegalStateException("staffing-required:" + capabilityRef());
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("capacity-unavailable:" + capabilityRef());
            }
            try {
                Thread.sleep(CAPACITY_RETRY_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("capacity-wait-interrupted:" + capabilityRef(), interrupted);
            }
        }
    }

    private boolean hasQualifiedParticipant() {
        return core.allWorkers().stream()
                .filter(w -> w.status() == WorkforceCoreService.WorkerStatus.ACTIVE)
                .filter(w -> supportsWorker(w.workerId()))
                .filter(w -> core.capabilities(w.workerId()).stream().anyMatch(c ->
                        c.capabilityRef().equals(capabilityRef()) && c.level() >= minimumCapabilityLevel()))
                .anyMatch(w -> core.participations(w.workerId()).stream()
                        .anyMatch(p -> p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE));
    }

    private void verifyAttribution(CapabilityResult result, WorkforceCoreService.Assignment assignment) {
        if (!assignment.workerId().equals(result.workerId())) {
            throw new IllegalStateException("execution-worker-mismatch:" + result.workerId());
        }
        if (!assignment.assignmentId().equals(result.assignmentReference())) {
            throw new IllegalStateException("execution-assignment-mismatch:" + result.assignmentReference());
        }
    }

    private void cancelIfActive(String assignmentId) {
        core.allAssignments().stream()
                .filter(a -> a.assignmentId().equals(assignmentId))
                .findFirst()
                .filter(a -> a.status() != WorkforceCoreService.AssignmentStatus.COMPLETED
                        && a.status() != WorkforceCoreService.AssignmentStatus.CANCELLED)
                .ifPresent(a -> core.transitionAssignment(assignmentId, WorkforceCoreService.AssignmentStatus.CANCELLED));
    }

    private void releaseIfActive(String reservationId) {
        core.allCapacityReservations().stream()
                .filter(r -> r.reservationId().equals(reservationId))
                .findFirst()
                .filter(r -> r.status() == WorkforceCoreService.ReservationStatus.ACTIVE)
                .ifPresent(r -> core.releaseCapacity(reservationId));
    }

    private String stableKey(CapabilityRequest request) {
        int fingerprint = Objects.hash(request.workSpec().requiredCapability(), request.workSpec().target(),
                request.workSpec().consequence(), request.workSpec().description());
        return request.idempotencyKey() + ":" + Integer.toUnsignedString(fingerprint, 16);
    }

    private static String requireReference(String value, String failure) {
        if (value == null || value.isBlank()) throw new SecurityException(failure);
        return value.trim();
    }
}
