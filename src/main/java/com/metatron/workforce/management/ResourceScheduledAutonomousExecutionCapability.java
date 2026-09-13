package com.metatron.workforce.management;

import com.metatron.workforce.execution.ExecutionAttemptContext;
import com.metatron.workforce.runtime.execution.ExecutionResourceAdmissionDecision;
import com.metatron.workforce.runtime.execution.ExecutionResourceAdmissionRequest;
import com.metatron.workforce.runtime.execution.ExecutionResourceScheduler;
import com.metatron.workforce.runtime.execution.ResourceClaim;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Infrastructure admission decorator used inside the governed ExecutionAttempt boundary.
 * It never selects Work or Worker. It consumes the already-created current ExecutionAttempt and an
 * upstream dispatch/scheduling eligibility reference, then waits for bounded execution capacity.
 */
public final class ResourceScheduledAutonomousExecutionCapability implements AutonomousExecutionCapability {
    private static final Duration DEFAULT_WAIT = Duration.ofSeconds(30);
    private static final long RETRY_MILLIS = 25L;

    private final AutonomousExecutionCapability delegate;
    private final ExecutionResourceScheduler scheduler;
    private final Clock clock;
    private final Duration wait;

    public ResourceScheduledAutonomousExecutionCapability(AutonomousExecutionCapability delegate,
                                                          ExecutionResourceScheduler scheduler,
                                                          Clock clock) {
        this(delegate, scheduler, clock, DEFAULT_WAIT);
    }

    ResourceScheduledAutonomousExecutionCapability(AutonomousExecutionCapability delegate,
                                                   ExecutionResourceScheduler scheduler,
                                                   Clock clock,
                                                   Duration wait) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.wait = Objects.requireNonNull(wait, "wait");
        if (wait.isNegative() || wait.isZero()) throw new IllegalArgumentException("wait must be positive");
    }

    @Override public String capabilityRef() { return delegate.capabilityRef(); }
    @Override public String capabilityDescription() { return delegate.capabilityDescription(); }
    @Override public String authorityReference() { return delegate.authorityReference(); }
    @Override public String authorizationReference() { return delegate.authorizationReference(); }
    @Override public double minimumCapabilityLevel() { return delegate.minimumCapabilityLevel(); }
    @Override public double requiredCapacity() { return delegate.requiredCapacity(); }
    @Override public boolean supportsWorker(String workerId) { return delegate.supportsWorker(workerId); }
    @Override public boolean supportsWorker(String workerId, com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec workSpec) {
        return delegate.supportsWorker(workerId, workSpec);
    }
    @Override public boolean supportsWork(com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec workSpec) {
        return delegate.supportsWork(workSpec);
    }
    @Override public boolean requiresIndependentObservation(com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec workSpec) {
        return delegate.requiresIndependentObservation(workSpec);
    }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        ExecutionAttemptContext.Binding attempt = ExecutionAttemptContext.current()
                .orElseThrow(() -> new SecurityException("EXECUTION_ATTEMPT_REQUIRED: resource scheduling requires current attempt"));
        if (!attempt.objectiveId().equals(request.objectiveId())) throw new SecurityException("resource scheduling objective mismatch");
        if (request.allocated() && !attempt.workerId().equals(request.allocatedWorkerId())) {
            throw new SecurityException("resource scheduling worker mismatch");
        }
        String eligibility = request.schedulingBound()
                ? request.schedulingDecisionReference()
                : request.dispatchBound() ? "dispatch-eligibility:" + request.dispatchReference() : "";
        if (eligibility.isBlank()) throw new SecurityException("upstream scheduling/dispatch eligibility required");

        Instant submitted = clock.instant();
        String requestId = "resource-admission:" + attempt.attemptId();
        ResourceClaim executionSlot = new ResourceClaim("execution-slot:" + attempt.attemptId(), attempt.attemptId(),
                "compute:execution", ResourceClaim.ResourceClass.COMPUTE, ResourceClaim.Mode.CAPACITY,
                1, "slot", true, "", Map.of("capabilityRef", capabilityRef()));
        scheduler.submit(new ExecutionResourceAdmissionRequest(requestId, attempt.attemptId(), attempt.fencingToken(),
                request.objectiveId(), eligibility, 50, submitted.plus(wait), List.of(executionSlot), "executor:local", submitted), submitted);

        ExecutionResourceAdmissionDecision admitted = awaitAdmission(requestId);
        try {
            CapabilityResult result = delegate.execute(request);
            List<String> evidence = new ArrayList<>(result.evidenceReferences());
            evidence.add("execution-resource-admission:" + requestId + ":workspace=" + admitted.workspaceRef()
                    + ":executor=" + admitted.executorRef());
            if (admitted.grant() != null) admitted.grant().leases().forEach(lease -> evidence.add(
                    "execution-resource-lease:" + lease.resourceId() + ":fence=" + lease.fencingToken()
                            + ":lease=" + lease.leaseId()));
            return new CapabilityResult(result.success(), result.workerId(), result.assignmentReference(),
                    result.workReference(), evidence, result.summary());
        } finally {
            try { scheduler.complete(requestId, clock.instant()); }
            catch (RuntimeException ignored) { }
        }
    }

    private ExecutionResourceAdmissionDecision awaitAdmission(String requestId) {
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            ExecutionResourceAdmissionDecision decision = scheduler.admit(requestId, clock.instant());
            if (decision.status() == ExecutionResourceAdmissionDecision.Status.ADMITTED) return decision;
            if (decision.status() == ExecutionResourceAdmissionDecision.Status.BLOCKED) {
                throw new IllegalStateException("resource-admission-blocked:" + decision.reason());
            }
            if (System.nanoTime() >= deadline) throw new IllegalStateException("resource-admission-timeout:" + decision.reason());
            try { Thread.sleep(RETRY_MILLIS); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("resource-admission-interrupted", interrupted);
            }
        }
    }
}
