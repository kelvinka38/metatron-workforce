package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.Assignment;
import com.metatron.workforce.execution.Authorization;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.execution.ExecutionRequest;
import com.metatron.workforce.execution.ExecutionState;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Governed Workforce boundary: staffing -> capacity -> Assignment -> Authorization -> effect. */
public final class GovernedAutonomousExecutionCapability implements AutonomousExecutionCapability {
    private static final Duration DEFAULT_CAPACITY_WAIT = Duration.ofSeconds(30);
    private static final long CAPACITY_RETRY_MILLIS = 25L;

    private final AutonomousExecutionCapability delegate;
    private final WorkforceCoreService core;
    private final ExecutionAdmissionService admission;
    private final Clock clock;
    private final Duration capacityWait;
    private final AutonomousStaffingService staffing;
    private final ThreadLocal<List<String>> staffingEvidence = ThreadLocal.withInitial(ArrayList::new);

    public GovernedAutonomousExecutionCapability(AutonomousExecutionCapability delegate,
                                                  WorkforceCoreService core,
                                                  ExecutionAdmissionService admission,
                                                  Clock clock) {
        this(delegate, core, admission, clock, DEFAULT_CAPACITY_WAIT, null);
    }

    public GovernedAutonomousExecutionCapability(AutonomousExecutionCapability delegate,
                                                  WorkforceCoreService core,
                                                  ExecutionAdmissionService admission,
                                                  Clock clock,
                                                  AutonomousStaffingService staffing) {
        this(delegate, core, admission, clock, DEFAULT_CAPACITY_WAIT, staffing);
    }

    GovernedAutonomousExecutionCapability(AutonomousExecutionCapability delegate,
                                          WorkforceCoreService core,
                                          ExecutionAdmissionService admission,
                                          Clock clock,
                                          Duration capacityWait) {
        this(delegate, core, admission, clock, capacityWait, null);
    }

    GovernedAutonomousExecutionCapability(AutonomousExecutionCapability delegate,
                                          WorkforceCoreService core,
                                          ExecutionAdmissionService admission,
                                          Clock clock,
                                          Duration capacityWait,
                                          AutonomousStaffingService staffing) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.core = Objects.requireNonNull(core, "core");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.capacityWait = Objects.requireNonNull(capacityWait, "capacityWait");
        this.staffing = staffing;
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
        staffingEvidence.get().clear();
        String authorityRef = requireReference(delegate.authorityReference(), "authority-reference-missing");
        String authorizationRef = requireReference(delegate.authorizationReference(), "authorization-reference-missing");

        WorkforceCoreService.Worker worker = awaitEligibleWorker();
        WorkforceCoreService.Participation participation = core.participations(worker.workerId()).stream()
                .filter(p -> p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE)
                .sorted(Comparator.comparing(WorkforceCoreService.Participation::participationId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("staffing-gap:no-active-participation:" + worker.workerId()));

        String key = stableKey(request);
        String reservationId = "capacity-reservation:" + key;
        String assignmentId = "assignment:" + key;
        String executionId = "execution:" + key;
        boolean assignmentCreated = false;
        try {
            core.reserveCapacity(reservationId, assignmentId, request.objectiveId(), worker.workerId(), delegate.requiredCapacity());
            WorkforceCoreService.Assignment coreAssignment = core.assignReserved(
                    reservationId, participation.participationId(), authorityRef, authorizationRef, request.workSpec().objective());
            assignmentCreated = true;

            ExecutionState admitted = admission.admit(new ExecutionRequest(
                    executionId,
                    new Assignment(coreAssignment.assignmentId(), coreAssignment.workerId()),
                    new Authorization(coreAssignment.authorizationRef(), coreAssignment.workerId()),
                    clock.instant()));
            if (admitted != ExecutionState.ADMITTED) throw new SecurityException("execution-not-admitted:" + admitted);

            CapabilityResult result = delegate.execute(request.withAllocation(
                    coreAssignment.workerId(), coreAssignment.assignmentId(), coreAssignment.authorizationRef()));
            verifyAttribution(result, coreAssignment);
            core.transitionAssignment(coreAssignment.assignmentId(), result.success()
                    ? WorkforceCoreService.AssignmentStatus.COMPLETED : WorkforceCoreService.AssignmentStatus.CANCELLED);

            List<String> evidence = new ArrayList<>(staffingEvidence.get());
            evidence.addAll(result.evidenceReferences());
            evidence.add("allocation:worker=" + coreAssignment.workerId()
                    + ":assignment=" + coreAssignment.assignmentId()
                    + ":reservation=" + reservationId
                    + ":capacity=" + delegate.requiredCapacity());
            evidence.add("execution-admission:execution=" + executionId
                    + ":authorization=" + coreAssignment.authorizationRef() + ":state=" + admitted);
            return new CapabilityResult(result.success(), result.workerId(), result.assignmentReference(),
                    result.workReference(), evidence, result.summary());
        } catch (RuntimeException failure) {
            if (assignmentCreated) cancelIfActive(assignmentId); else releaseIfActive(reservationId);
            throw failure;
        } finally {
            staffingEvidence.remove();
        }
    }

    private WorkforceCoreService.Worker awaitEligibleWorker() {
        long deadline = System.nanoTime() + capacityWait.toNanos();
        boolean staffingAttempted = false;
        while (true) {
            List<WorkforceCoreService.Worker> eligible = core.eligibleWorkers(
                            capabilityRef(), minimumCapabilityLevel(), requiredCapacity(), clock.instant()).stream()
                    .filter(w -> supportsWorker(w.workerId()))
                    .toList();
            if (!eligible.isEmpty()) return eligible.getFirst();

            if (!hasQualifiedParticipant() && !staffingAttempted) {
                staffingAttempted = true;
                if (staffing == null) throw new IllegalStateException("staffing-gap:orchestrator-unavailable:" + capabilityRef());
                AutonomousStaffingService.StaffingOutcome outcome = staffing.ensureStaffed(delegate, clock.instant());
                staffingEvidence.get().addAll(outcome.evidenceReferences());
                continue;
            }
            if (System.nanoTime() >= deadline) throw new IllegalStateException("capacity-unavailable:" + capabilityRef());
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
        if (!assignment.workerId().equals(result.workerId())) throw new IllegalStateException("execution-worker-mismatch:" + result.workerId());
        if (!assignment.assignmentId().equals(result.assignmentReference())) throw new IllegalStateException("execution-assignment-mismatch:" + result.assignmentReference());
    }

    private void cancelIfActive(String assignmentId) {
        core.allAssignments().stream().filter(a -> a.assignmentId().equals(assignmentId)).findFirst()
                .filter(a -> a.status() != WorkforceCoreService.AssignmentStatus.COMPLETED
                        && a.status() != WorkforceCoreService.AssignmentStatus.CANCELLED)
                .ifPresent(a -> core.transitionAssignment(assignmentId, WorkforceCoreService.AssignmentStatus.CANCELLED));
    }

    private void releaseIfActive(String reservationId) {
        core.allCapacityReservations().stream().filter(r -> r.reservationId().equals(reservationId)).findFirst()
                .filter(r -> r.status() == WorkforceCoreService.ReservationStatus.ACTIVE)
                .ifPresent(r -> core.releaseCapacity(reservationId));
    }

    private String stableKey(CapabilityRequest request) {
        int fingerprint = Objects.hash(request.workSpec().requiredCapability(), request.workSpec().target(),
                request.workSpec().consequence(), request.workSpec().objective());
        return request.idempotencyKey() + ":" + Integer.toUnsignedString(fingerprint, 16);
    }

    private static String requireReference(String value, String failure) {
        if (value == null || value.isBlank()) throw new SecurityException(failure);
        return value.trim();
    }
}
