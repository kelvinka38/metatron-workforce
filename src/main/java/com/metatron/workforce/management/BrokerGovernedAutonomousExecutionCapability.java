package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thin Work-execution binding for privileged capabilities whose downstream broker is the
 * actual effect security boundary. It deliberately does not restaff, reserve Worker capacity,
 * create a second Core Assignment, provision a generic runtime, or derive a second SoT plan.
 * The durable scheduler-selected Worker is validated once, the Work binding is stable, and the
 * existing ExecutionAttempt lease/fencing authority protects replay/restart semantics.
 */
public final class BrokerGovernedAutonomousExecutionCapability implements AutonomousExecutionCapability {
    private static final Duration EXECUTION_LEASE = Duration.ofMinutes(10);
    private static final Duration HEARTBEAT_EXTENSION = Duration.ofMinutes(10);
    private static final long HEARTBEAT_PERIOD_MILLIS = 5_000L;

    private final AutonomousExecutionCapability delegate;
    private final WorkforceCoreService core;
    private final ExecutionAttemptService attempts;
    private final Clock clock;

    public BrokerGovernedAutonomousExecutionCapability(AutonomousExecutionCapability delegate,
                                                        WorkforceCoreService core,
                                                        ExecutionAttemptService attempts,
                                                        Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.core = Objects.requireNonNull(core, "core");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public String capabilityRef() { return delegate.capabilityRef(); }
    @Override public String capabilityDescription() { return delegate.capabilityDescription(); }
    @Override public String authorityReference() { return delegate.authorityReference(); }
    @Override public String authorizationReference() { return delegate.authorizationReference(); }
    @Override public double minimumCapabilityLevel() { return delegate.minimumCapabilityLevel(); }
    @Override public double requiredCapacity() { return delegate.requiredCapacity(); }
    @Override public boolean supportsWorker(String workerId) { return delegate.supportsWorker(workerId); }
    @Override public boolean supportsWorker(String workerId, ExecutionWorkSpec workSpec) { return delegate.supportsWorker(workerId, workSpec); }
    @Override public boolean supportsWork(ExecutionWorkSpec workSpec) { return delegate.supportsWork(workSpec); }
    @Override public boolean requiresIndependentObservation(ExecutionWorkSpec workSpec) { return delegate.requiresIndependentObservation(workSpec); }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.dispatchBound()) throw new SecurityException("durable dispatch required for broker-governed Work");
        String workerId = requireScheduledWorker(request);
        String authorizationRef = requireReference(delegate.authorizationReference(), "authorization-reference-missing");
        validateWorker(workerId, request.workSpec());

        String workBinding = "work-binding:" + request.objectiveId() + ":" + request.workSpec().stepId() + ":" + workerId;
        CapabilityRequest bound = request.withAllocation(workerId, workBinding, authorizationRef);
        int attemptNumber = Math.max(1, request.dispatchAttempt());
        ExecutionAttempt attempt = attempts.begin(
                request.dispatchReference(), request.objectiveId(), request.workSpec().stepId(), workerId,
                workBinding, authorizationRef, "broker:" + delegate.capabilityRef(), attemptNumber, EXECUTION_LEASE, clock.instant());
        bound = bound.withExecutionAttempt(attempt.attemptId(), attempt.fencingToken());
        attempts.checkpoint(attempt.attemptId(), attempt.fencingToken(), "broker-bound:" + workBinding, clock.instant());

        AtomicReference<RuntimeException> heartbeatFailure = new AtomicReference<>();
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "metatron-broker-execution-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            try { attempts.heartbeat(attempt.attemptId(), attempt.fencingToken(), HEARTBEAT_EXTENSION, clock.instant()); }
            catch (RuntimeException failure) { heartbeatFailure.compareAndSet(null, failure); }
        }, HEARTBEAT_PERIOD_MILLIS, HEARTBEAT_PERIOD_MILLIS, TimeUnit.MILLISECONDS);

        try {
            CapabilityResult result = delegate.execute(bound);
            RuntimeException leaseFailure = heartbeatFailure.get();
            if (leaseFailure != null) throw leaseFailure;
            verifyAttribution(result, workerId, workBinding);
            ExecutionAttempt terminal = result.success()
                    ? attempts.succeed(attempt.attemptId(), attempt.fencingToken(), clock.instant())
                    : attempts.fail(attempt.attemptId(), attempt.fencingToken(), nonBlank(result.summary(), "broker-capability-unsuccessful"), clock.instant());
            List<String> evidence = new ArrayList<>(result.evidenceReferences());
            evidence.add("work-execution-binding:worker=" + workerId + ":binding=" + workBinding + ":authorization=" + authorizationRef);
            evidence.add("execution-attempt:" + terminal.attemptId() + ":fence=" + terminal.fencingToken() + ":status=" + terminal.status());
            return new CapabilityResult(result.success(), result.workerId(), result.assignmentReference(),
                    result.workReference(), List.copyOf(evidence), result.summary());
        } catch (RuntimeException failure) {
            try { attempts.fail(attempt.attemptId(), attempt.fencingToken(), nonBlank(failure.getMessage(), failure.getClass().getSimpleName()), clock.instant()); }
            catch (RuntimeException ignored) { }
            throw failure;
        } finally {
            heartbeat.shutdownNow();
        }
    }

    private String requireScheduledWorker(CapabilityRequest request) {
        String workerId = request.scheduledWorkerId() == null ? "" : request.scheduledWorkerId().trim();
        if (workerId.isBlank()) throw new SecurityException("scheduled Worker required for broker-governed Work");
        return workerId;
    }

    private void validateWorker(String workerId, ExecutionWorkSpec workSpec) {
        WorkforceCoreService.Worker worker = core.worker(workerId);
        if (worker.status() != WorkforceCoreService.WorkerStatus.ACTIVE) throw new SecurityException("scheduled Worker is not active: " + workerId);
        if (!delegate.supportsWorker(workerId, workSpec)) throw new SecurityException("scheduled Worker does not support Work: " + workerId);
        boolean capable = core.capabilities(workerId).stream().anyMatch(c ->
                c.capabilityRef().equals(delegate.capabilityRef()) && c.level() >= delegate.minimumCapabilityLevel());
        if (!capable) throw new SecurityException("scheduled Worker capability missing: " + delegate.capabilityRef());
        boolean participating = core.participations(workerId).stream().anyMatch(p -> p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE);
        if (!participating) throw new SecurityException("scheduled Worker has no active participation: " + workerId);
    }

    private static void verifyAttribution(CapabilityResult result, String workerId, String workBinding) {
        if (!workerId.equals(result.workerId())) throw new IllegalStateException("execution-worker-mismatch:" + result.workerId());
        if (!workBinding.equals(result.assignmentReference())) throw new IllegalStateException("execution-binding-mismatch:" + result.assignmentReference());
    }

    private static String requireReference(String value, String failure) {
        if (value == null || value.isBlank()) throw new SecurityException(failure);
        return value.trim();
    }

    private static String nonBlank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}
