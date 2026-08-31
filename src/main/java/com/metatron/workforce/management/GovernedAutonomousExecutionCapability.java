package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.Assignment;
import com.metatron.workforce.execution.Authorization;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.execution.ExecutionRequest;
import com.metatron.workforce.execution.ExecutionState;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeInstance;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Governed Workforce execution boundary:
 * staffing -> capacity -> Assignment -> Authorization -> Execution attempt/runtime -> effect.
 *
 * Execution owns attempt identity, lease/heartbeat/checkpoint/fencing. Runtime owns replaceable
 * computational embodiment. Worker and Assignment identity remain stable across runtime recovery.
 */
public final class GovernedAutonomousExecutionCapability implements AutonomousExecutionCapability {
    private static final Duration DEFAULT_CAPACITY_WAIT = Duration.ofSeconds(30);
    private static final Duration EXECUTION_LEASE = Duration.ofSeconds(30);
    private static final Duration HEARTBEAT_EXTENSION = Duration.ofSeconds(30);
    private static final long HEARTBEAT_PERIOD_MILLIS = 5_000L;
    private static final long CAPACITY_RETRY_MILLIS = 25L;
    private static final int MAX_READ_ONLY_RUNTIME_ATTEMPTS = 2;

    private final AutonomousExecutionCapability delegate;
    private final WorkforceCoreService core;
    private final ExecutionAdmissionService admission;
    private final Clock clock;
    private final Duration capacityWait;
    private final AutonomousStaffingService staffing;
    private final ExecutionAttemptService executionAttempts;
    private final RuntimeCapacityCoordinator runtimeCapacity;
    private final ThreadLocal<List<String>> staffingEvidence = ThreadLocal.withInitial(ArrayList::new);

    public GovernedAutonomousExecutionCapability(AutonomousExecutionCapability delegate,
                                                  WorkforceCoreService core,
                                                  ExecutionAdmissionService admission,
                                                  Clock clock) {
        this(delegate, core, admission, clock, DEFAULT_CAPACITY_WAIT, null, null, null);
    }

    public GovernedAutonomousExecutionCapability(AutonomousExecutionCapability delegate,
                                                  WorkforceCoreService core,
                                                  ExecutionAdmissionService admission,
                                                  Clock clock,
                                                  AutonomousStaffingService staffing) {
        this(delegate, core, admission, clock, DEFAULT_CAPACITY_WAIT, staffing, null, null);
    }

    public GovernedAutonomousExecutionCapability(AutonomousExecutionCapability delegate,
                                                  WorkforceCoreService core,
                                                  ExecutionAdmissionService admission,
                                                  Clock clock,
                                                  AutonomousStaffingService staffing,
                                                  ExecutionAttemptService executionAttempts,
                                                  RuntimeCapacityCoordinator runtimeCapacity) {
        this(delegate, core, admission, clock, DEFAULT_CAPACITY_WAIT, staffing, executionAttempts, runtimeCapacity);
    }

    GovernedAutonomousExecutionCapability(AutonomousExecutionCapability delegate,
                                          WorkforceCoreService core,
                                          ExecutionAdmissionService admission,
                                          Clock clock,
                                          Duration capacityWait) {
        this(delegate, core, admission, clock, capacityWait, null, null, null);
    }

    GovernedAutonomousExecutionCapability(AutonomousExecutionCapability delegate,
                                          WorkforceCoreService core,
                                          ExecutionAdmissionService admission,
                                          Clock clock,
                                          Duration capacityWait,
                                          AutonomousStaffingService staffing) {
        this(delegate, core, admission, clock, capacityWait, staffing, null, null);
    }

    GovernedAutonomousExecutionCapability(AutonomousExecutionCapability delegate,
                                          WorkforceCoreService core,
                                          ExecutionAdmissionService admission,
                                          Clock clock,
                                          Duration capacityWait,
                                          AutonomousStaffingService staffing,
                                          ExecutionAttemptService executionAttempts,
                                          RuntimeCapacityCoordinator runtimeCapacity) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.core = Objects.requireNonNull(core, "core");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.capacityWait = Objects.requireNonNull(capacityWait, "capacityWait");
        this.staffing = staffing;
        this.executionAttempts = executionAttempts;
        this.runtimeCapacity = runtimeCapacity;
        if ((executionAttempts == null) != (runtimeCapacity == null)) {
            throw new IllegalArgumentException("ExecutionAttemptService and RuntimeCapacityCoordinator must be configured together");
        }
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

        String key = allocationKey(request);
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

            CapabilityRequest allocated = request.withAllocation(
                    coreAssignment.workerId(), coreAssignment.assignmentId(), coreAssignment.authorizationRef());
            CapabilityResult result = executionAttempts == null
                    ? delegate.execute(allocated)
                    : executeWithRecovery(allocated, coreAssignment);
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

    private CapabilityResult executeWithRecovery(CapabilityRequest request,
                                                  WorkforceCoreService.Assignment assignment) {
        executionAttempts.reconcileExpired(clock.instant());
        String dispatchRef = request.dispatchBound() ? request.dispatchReference() : "dispatch:" + stableKey(request);
        int dispatchAttempt = request.dispatchBound() ? request.dispatchAttempt() : 1;
        RuntimeInstance runtime = runtimeCapacity.provision(assignment.workerId());
        List<String> executionEvidence = new ArrayList<>();

        int maxAttempts = request.workSpec().consequence() == ExecutionWorkSpec.Consequence.READ_ONLY
                ? MAX_READ_ONLY_RUNTIME_ATTEMPTS : 1;
        RuntimeException terminalFailure = null;
        try {
            for (int recoveryIndex = 1; recoveryIndex <= maxAttempts; recoveryIndex++) {
                int attemptNumber = ((dispatchAttempt - 1) * MAX_READ_ONLY_RUNTIME_ATTEMPTS) + recoveryIndex;
                ExecutionAttempt attempt = executionAttempts.begin(
                        dispatchRef, request.objectiveId(), request.workSpec().stepId(), assignment.workerId(),
                        assignment.assignmentId(), assignment.authorizationRef(), runtime.runtimeId(), attemptNumber,
                        EXECUTION_LEASE, clock.instant());
                executionAttempts.heartbeat(attempt.attemptId(), attempt.fencingToken(), HEARTBEAT_EXTENSION, clock.instant());
                executionAttempts.checkpoint(attempt.attemptId(), attempt.fencingToken(),
                        "prepared:" + request.idempotencyKey(), clock.instant());

                AtomicReference<RuntimeException> heartbeatFailure = new AtomicReference<>();
                ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "metatron-execution-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                });
                heartbeat.scheduleAtFixedRate(() -> {
                    try {
                        executionAttempts.heartbeat(attempt.attemptId(), attempt.fencingToken(),
                                HEARTBEAT_EXTENSION, clock.instant());
                    } catch (RuntimeException failure) {
                        heartbeatFailure.compareAndSet(null, failure);
                    }
                }, HEARTBEAT_PERIOD_MILLIS, HEARTBEAT_PERIOD_MILLIS, TimeUnit.MILLISECONDS);

                try {
                    CapabilityResult result = delegate.execute(request);
                    RuntimeException leaseFailure = heartbeatFailure.get();
                    if (leaseFailure != null) throw leaseFailure;
                    verifyAttribution(result, assignment);
                    if (result.success()) {
                        ExecutionAttempt completed = executionAttempts.succeed(
                                attempt.attemptId(), attempt.fencingToken(), clock.instant());
                        executionEvidence.add(attemptEvidence(completed));
                        List<String> refs = new ArrayList<>(result.evidenceReferences());
                        refs.addAll(executionEvidence);
                        return new CapabilityResult(true, result.workerId(), result.assignmentReference(),
                                result.workReference(), refs, result.summary());
                    }
                    ExecutionAttempt failed = executionAttempts.fail(
                            attempt.attemptId(), attempt.fencingToken(), nonBlank(result.summary(), "capability-unsuccessful"), clock.instant());
                    executionEvidence.add(attemptEvidence(failed));
                    List<String> refs = new ArrayList<>(result.evidenceReferences());
                    refs.addAll(executionEvidence);
                    return new CapabilityResult(false, result.workerId(), result.assignmentReference(),
                            result.workReference(), refs, result.summary());
                } catch (RuntimeException failure) {
                    terminalFailure = failure;
                    failAttemptIfOwned(attempt, failure);
                    executionEvidence.add("execution-attempt:" + attempt.attemptId()
                            + ":runtime=" + attempt.runtimeId()
                            + ":fence=" + attempt.fencingToken()
                            + ":failure=" + failure.getClass().getSimpleName());
                    if (!retryableReadOnly(request, failure) || recoveryIndex >= maxAttempts) throw failure;
                    RuntimeInstance replacement = runtimeCapacity.replace(assignment.workerId(), runtime.runtimeId());
                    executionEvidence.add("runtime-replaced:worker=" + assignment.workerId()
                            + ":failed=" + runtime.runtimeId() + ":replacement=" + replacement.runtimeId());
                    runtime = replacement;
                } finally {
                    heartbeat.shutdownNow();
                }
            }
            throw terminalFailure == null
                    ? new IllegalStateException("execution recovery exhausted") : terminalFailure;
        } finally {
            releaseRuntimeIfPresent(assignment.workerId(), runtime);
        }
    }

    private void failAttemptIfOwned(ExecutionAttempt attempt, RuntimeException failure) {
        try {
            executionAttempts.fail(attempt.attemptId(), attempt.fencingToken(),
                    nonBlank(failure.getMessage(), failure.getClass().getSimpleName()), clock.instant());
        } catch (RuntimeException staleOrTerminal) {
            // A stale/expired attempt must remain fenced; do not overwrite the authoritative terminal state.
        }
    }

    private boolean retryableReadOnly(CapabilityRequest request, RuntimeException failure) {
        return request.workSpec().consequence() == ExecutionWorkSpec.Consequence.READ_ONLY
                && !(failure instanceof SecurityException)
                && !(failure instanceof IllegalArgumentException);
    }

    private String attemptEvidence(ExecutionAttempt attempt) {
        return "execution-attempt:" + attempt.attemptId()
                + ":dispatch=" + attempt.dispatchId()
                + ":runtime=" + attempt.runtimeId()
                + ":fence=" + attempt.fencingToken()
                + ":checkpoint=" + attempt.checkpointRef()
                + ":status=" + attempt.status();
    }

    private void releaseRuntimeIfPresent(String workerId, RuntimeInstance runtime) {
        if (runtime == null) return;
        try { runtimeCapacity.release(workerId, runtime.runtimeId()); }
        catch (RuntimeException ignored) { /* recovery/reconciliation owns any remaining runtime state */ }
    }

    private WorkforceCoreService.Worker awaitEligibleWorker() {
        long deadline = System.nanoTime() + capacityWait.toNanos();
        boolean staffingAttempted = false;
        while (true) {
            reconcileTerminalExecutionCapacity();
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

    /** Reconcile Core capacity only when Execution proves that ownership is terminal. */
    private void reconcileTerminalExecutionCapacity() {
        if (executionAttempts == null) return;
        executionAttempts.reconcileExpired(clock.instant());
        List<ExecutionAttempt> attempts = executionAttempts.all();
        for (WorkforceCoreService.Assignment assignment : core.allAssignments()) {
            if (!supportsWorker(assignment.workerId())) continue;
            if (assignment.status() == WorkforceCoreService.AssignmentStatus.COMPLETED
                    || assignment.status() == WorkforceCoreService.AssignmentStatus.CANCELLED) {
                releaseReservationsForTerminalAssignment(assignment.assignmentId());
                continue;
            }
            List<ExecutionAttempt> ownedAttempts = attempts.stream()
                    .filter(attempt -> attempt.assignmentRef().equals(assignment.assignmentId()))
                    .toList();
            if (ownedAttempts.isEmpty() || ownedAttempts.stream().anyMatch(attempt -> !attempt.terminal())) continue;

            WorkforceCoreService.AssignmentStatus reconciledStatus = ownedAttempts.stream()
                    .anyMatch(attempt -> attempt.status() == ExecutionAttempt.Status.SUCCEEDED)
                    ? WorkforceCoreService.AssignmentStatus.COMPLETED
                    : WorkforceCoreService.AssignmentStatus.CANCELLED;
            core.transitionAssignment(assignment.assignmentId(), reconciledStatus);
            staffingEvidence.get().add("capacity-reconciled:assignment=" + assignment.assignmentId()
                    + ":status=" + reconciledStatus + ":attempts=" + ownedAttempts.size());
        }
    }

    private void releaseReservationsForTerminalAssignment(String assignmentId) {
        core.allCapacityReservations().stream()
                .filter(reservation -> reservation.assignmentId().equals(assignmentId))
                .filter(reservation -> reservation.status() == WorkforceCoreService.ReservationStatus.ACTIVE)
                .map(WorkforceCoreService.CapacityReservation::reservationId)
                .toList()
                .forEach(core::releaseCapacity);
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

    private String allocationKey(CapabilityRequest request) {
        String key = stableKey(request);
        if (!request.dispatchBound()) return key;
        int dispatchFingerprint = Objects.hash(request.dispatchReference(), request.dispatchAttempt());
        return key + ":dispatch=" + Integer.toUnsignedString(dispatchFingerprint, 16);
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

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
