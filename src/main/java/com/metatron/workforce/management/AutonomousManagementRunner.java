package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionPlanProposalService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.observation.ObservationClosureService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Restartable Workforce management loop. The runner owns operational progression after durable
 * Objective acceptance. Execution completion is never Objective completion when an Observation
 * boundary is configured: every required criterion must be independently observed first.
 * Resource/control safety is evaluated before a durable dispatch may become active.
 */
public final class AutonomousManagementRunner implements AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AutonomousManagementRunner.class);
    private static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);
    private static final Duration DEFAULT_POLL = Duration.ofSeconds(5);
    private static final int DEFAULT_PARALLELISM = 4;
    private static final int MAX_READ_ONLY_DISPATCH_ATTEMPTS = 3;

    private final ManagementAutonomyService management;
    private final ExecutionPlanProposalService planner;
    private final Map<String, AutonomousExecutionCapability> capabilities;
    private final AutonomyCoordinationService coordination;
    private final ObservationClosureService observationClosure;
    private final AutonomySafetyService safety;
    private final Clock clock;
    private final String runnerId;
    private final Duration leaseDuration;
    private final Duration pollInterval;
    private final ScheduledExecutorService executor;
    private final ExecutorService workExecutor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final ReentrantLock runLock = new ReentrantLock();
    private volatile AutonomySchedulingService scheduling;

    public AutonomousManagementRunner(ManagementAutonomyService management,
                                      ExecutionPlanProposalService planner,
                                      List<AutonomousExecutionCapability> executionCapabilities,
                                      Clock clock) {
        this(management, planner, executionCapabilities, new AutonomyCoordinationService(), null, null, clock,
                "management-runner:" + UUID.randomUUID(), DEFAULT_LEASE, DEFAULT_POLL, DEFAULT_PARALLELISM);
    }

    public AutonomousManagementRunner(ManagementAutonomyService management,
                                      ExecutionPlanProposalService planner,
                                      List<AutonomousExecutionCapability> executionCapabilities,
                                      AutonomyCoordinationService coordination,
                                      Clock clock) {
        this(management, planner, executionCapabilities, coordination, null, null, clock,
                "management-runner:" + UUID.randomUUID(), DEFAULT_LEASE, DEFAULT_POLL, DEFAULT_PARALLELISM);
    }

    public AutonomousManagementRunner(ManagementAutonomyService management,
                                      ExecutionPlanProposalService planner,
                                      List<AutonomousExecutionCapability> executionCapabilities,
                                      AutonomyCoordinationService coordination,
                                      ObservationClosureService observationClosure,
                                      Clock clock) {
        this(management, planner, executionCapabilities, coordination,
                Objects.requireNonNull(observationClosure, "observationClosure"), null, clock,
                "management-runner:" + UUID.randomUUID(), DEFAULT_LEASE, DEFAULT_POLL, DEFAULT_PARALLELISM);
    }

    public AutonomousManagementRunner(ManagementAutonomyService management,
                                      ExecutionPlanProposalService planner,
                                      List<AutonomousExecutionCapability> executionCapabilities,
                                      AutonomyCoordinationService coordination,
                                      ObservationClosureService observationClosure,
                                      AutonomySafetyService safety,
                                      Clock clock) {
        this(management, planner, executionCapabilities, coordination,
                Objects.requireNonNull(observationClosure, "observationClosure"),
                Objects.requireNonNull(safety, "safety"), clock,
                "management-runner:" + UUID.randomUUID(), DEFAULT_LEASE, DEFAULT_POLL, DEFAULT_PARALLELISM);
    }

    AutonomousManagementRunner(ManagementAutonomyService management,
                               ExecutionPlanProposalService planner,
                               List<AutonomousExecutionCapability> executionCapabilities,
                               Clock clock, String runnerId, Duration leaseDuration,
                               Duration pollInterval) {
        this(management, planner, executionCapabilities, new AutonomyCoordinationService(), null, null, clock,
                runnerId, leaseDuration, pollInterval, DEFAULT_PARALLELISM);
    }

    AutonomousManagementRunner(ManagementAutonomyService management,
                               ExecutionPlanProposalService planner,
                               List<AutonomousExecutionCapability> executionCapabilities,
                               AutonomyCoordinationService coordination,
                               Clock clock, String runnerId, Duration leaseDuration,
                               Duration pollInterval, int parallelism) {
        this(management, planner, executionCapabilities, coordination, null, null, clock,
                runnerId, leaseDuration, pollInterval, parallelism);
    }

    AutonomousManagementRunner(ManagementAutonomyService management,
                               ExecutionPlanProposalService planner,
                               List<AutonomousExecutionCapability> executionCapabilities,
                               AutonomyCoordinationService coordination,
                               ObservationClosureService observationClosure,
                               Clock clock, String runnerId, Duration leaseDuration,
                               Duration pollInterval, int parallelism) {
        this(management, planner, executionCapabilities, coordination, observationClosure, null, clock,
                runnerId, leaseDuration, pollInterval, parallelism);
    }

    AutonomousManagementRunner(ManagementAutonomyService management,
                               ExecutionPlanProposalService planner,
                               List<AutonomousExecutionCapability> executionCapabilities,
                               AutonomyCoordinationService coordination,
                               ObservationClosureService observationClosure,
                               AutonomySafetyService safety,
                               Clock clock, String runnerId, Duration leaseDuration,
                               Duration pollInterval, int parallelism) {
        this.management = Objects.requireNonNull(management, "management");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.coordination = Objects.requireNonNull(coordination, "coordination");
        this.observationClosure = observationClosure;
        this.safety = safety;
        Objects.requireNonNull(executionCapabilities, "executionCapabilities");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runnerId = requireText(runnerId, "runnerId");
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        this.pollInterval = requirePositive(pollInterval, "pollInterval");
        if (parallelism < 1) throw new IllegalArgumentException("parallelism must be positive");
        Map<String, AutonomousExecutionCapability> registered = new LinkedHashMap<>();
        for (AutonomousExecutionCapability capability : executionCapabilities) {
            String ref = requireText(capability.capabilityRef(), "capabilityRef");
            if (registered.putIfAbsent(ref, capability) != null) {
                throw new IllegalStateException("duplicate autonomous execution capability: " + ref);
            }
        }
        this.capabilities = Map.copyOf(registered);
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "metatron-autonomous-management");
            thread.setDaemon(true);
            return thread;
        });
        this.workExecutor = Executors.newFixedThreadPool(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "metatron-work-dispatch");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Configure the production scheduler before start; compatibility tests may omit it. */
    public AutonomousManagementRunner configureScheduling(AutonomySchedulingService scheduling) {
        if (started.get()) throw new IllegalStateException("scheduling must be configured before runner start");
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
        return this;
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(this::runSafely, 0,
                    pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public void wake() {
        if (!started.get()) return;
        executor.execute(this::runSafely);
    }

    public void runOnce() {
        if (!runLock.tryLock()) return;
        try {
            for (AutonomousObjectiveWork work : management.runnableAutonomousWork()) {
                processWithLease(work.objectiveId());
            }
        } finally {
            runLock.unlock();
        }
    }

    private void runSafely() {
        try { runOnce(); }
        catch (RuntimeException failure) { LOG.error("Autonomous management pass failed", failure); }
    }

    private void processWithLease(String objectiveId) {
        Instant now = clock.instant();
        ManagementLease lease = management.acquireManagementLease(
                objectiveId, runnerId, leaseDuration, now).orElse(null);
        if (lease == null) return;
        try { process(objectiveId, lease); }
        catch (RuntimeException failure) { blockIfLeaseActive(objectiveId, lease, classify(failure)); }
        finally { releaseIfOwned(objectiveId, lease); }
    }

    private void process(String objectiveId, ManagementLease lease) {
        AutonomousObjectiveWork work = management.findAutonomousWork(objectiveId).orElseThrow();
        if (work.status() == AutonomousObjectiveWork.Status.PENDING_PLANNING
                || work.status() == AutonomousObjectiveWork.Status.PLANNING) {
            if (work.status() == AutonomousObjectiveWork.Status.PENDING_PLANNING) {
                work = management.beginPlanning(objectiveId, runnerId, lease.token(), clock.instant());
            }
            List<ExecutionWorkSpec> proposed = planner.propose(
                    work.caseId(), work.normalizedRequest(), capabilityCatalog());
            work = management.recordPlan(objectiveId, runnerId, lease.token(), proposed, clock.instant());
        }

        if (work.status() != AutonomousObjectiveWork.Status.READY
                && work.status() != AutonomousObjectiveWork.Status.EXECUTING) return;

        DurableWorkGraph graph = coordination.ensureGraph(objectiveId, work.plannedWork(), clock.instant());
        work = management.beginExecution(objectiveId, runnerId, lease.token(), clock.instant());

        List<String> unsafeInterrupted = coordination.reconcileInterrupted(
                objectiveId, graph.graphVersion(), clock.instant());
        if (!unsafeInterrupted.isEmpty()) {
            management.blockAutonomousObjective(objectiveId, runnerId, lease.token(),
                    "execution-reconciliation-required:" + String.join(",", unsafeInterrupted), clock.instant());
            return;
        }
        work = reconcileSucceededNodes(objectiveId, graph.graphVersion(), work, lease);

        while (work.completedStepIds().size() < work.plannedWork().size()) {
            graph = coordination.activeGraph(objectiveId).orElseThrow();
            List<DurableWorkGraph.Node> ready = coordination.readyNodes(objectiveId, graph.graphVersion());
            if (ready.isEmpty()) {
                management.blockAutonomousObjective(objectiveId, runnerId, lease.token(),
                        "dependency-or-dispatch-failure:no-runnable-work", clock.instant());
                return;
            }

            String schedulingDecisionId = "";
            AutonomySchedulingService activeScheduling = scheduling;
            if (activeScheduling != null) {
                AutonomySchedulingDecision decision = activeScheduling.decide(
                        objectiveId, graph.graphVersion(), ready, capabilities, clock.instant());
                schedulingDecisionId = decision.decisionId();
                Set<String> selected = new LinkedHashSet<>(decision.selectedStepIds());
                ready = ready.stream().filter(node -> selected.contains(node.spec().stepId())).toList();
                if (ready.isEmpty()) {
                    String reasons = decision.deferredReasons().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(entry -> entry.getKey() + "=" + entry.getValue())
                            .reduce((left, right) -> left + "," + right).orElse("no-admissible-work");
                    boolean temporaryCapacityOnly = !decision.deferredReasons().isEmpty()
                            && decision.deferredReasons().values().stream()
                            .allMatch("finite-capacity-unavailable"::equals);
                    if (!temporaryCapacityOnly) {
                        management.blockAutonomousObjective(objectiveId, runnerId, lease.token(),
                                "scheduler-admission-blocked:" + reasons, clock.instant());
                    }
                    return;
                }
            }

            List<Future<NodeExecutionOutcome>> futures = new ArrayList<>();
            int graphVersion = graph.graphVersion();
            String schedulerRef = schedulingDecisionId;
            for (DurableWorkGraph.Node node : ready) {
                AutonomousExecutionCapability capability = capabilities.get(node.spec().requiredCapability());
                if (capability == null) {
                    futures.add(java.util.concurrent.CompletableFuture.completedFuture(
                            NodeExecutionOutcome.missing(node.spec().stepId(), node.spec().requiredCapability())));
                } else {
                    AutonomousObjectiveWork dispatchContext = work;
                    int plannedAttempt = node.attempt() + 1;
                    futures.add(workExecutor.submit(() -> executeNode(
                            objectiveId, graphVersion, plannedAttempt, dispatchContext, node.spec(), capability,
                            schedulerRef)));
                }
            }

            boolean stop = false;
            for (Future<NodeExecutionOutcome> future : futures) {
                NodeExecutionOutcome outcome = await(future);
                if (outcome.missingCapability()) {
                    String owner = management.get(objectiveId).ownerWorkerId();
                    management.assessCapacity(objectiveId, owner, outcome.requiredCapability(), 1.0, 0.0, clock.instant());
                    management.blockAutonomousObjective(objectiveId, runnerId, lease.token(),
                            "staffing-required:" + outcome.requiredCapability(), clock.instant());
                    stop = true;
                    continue;
                }
                if (!outcome.success()) {
                    if (outcome.recoverableReadOnlyFailure()) {
                        work = recoverReadOnlyFailure(objectiveId, graphVersion, outcome, lease);
                        continue;
                    }
                    String reason = outcome.failure().startsWith("autonomy-safety-gate:")
                            ? outcome.failure()
                            : "worker-execution-failure:" + outcome.stepId() + ":" + outcome.failure();
                    management.blockAutonomousObjective(objectiveId, runnerId, lease.token(), reason, clock.instant());
                    stop = true;
                    continue;
                }
                String owner = management.get(objectiveId).ownerWorkerId();
                if (!outcome.assignmentReference().isBlank()) {
                    management.addAssignmentReference(objectiveId, owner,
                            outcome.assignmentReference(), clock.instant());
                }
                management.recordStepCompleted(objectiveId, runnerId, lease.token(),
                        outcome.stepId(), outcome.evidenceReferences(), clock.instant());
            }
            if (stop) return;
            work = management.findAutonomousWork(objectiveId).orElseThrow();
        }

        graph = coordination.activeGraph(objectiveId).orElseThrow();
        if (observationClosure != null) {
            observationClosure.ensureRequirements(objectiveId, work.plannedWork(), clock.instant());
            observationClosure.observeAvailable(objectiveId, work.evidenceReferences(), clock.instant());
            ObservationClosureService.Verdict verdict = observationClosure.verdict(objectiveId);
            if (verdict == ObservationClosureService.Verdict.PENDING) return;
            if (verdict == ObservationClosureService.Verdict.FAILED
                    || verdict == ObservationClosureService.Verdict.INCONCLUSIVE) {
                management.blockAutonomousObjective(objectiveId, runnerId, lease.token(),
                        "observation-" + verdict.name().toLowerCase(java.util.Locale.ROOT), clock.instant());
                return;
            }
        }
        coordination.completeGraph(objectiveId, graph.graphVersion(), clock.instant());
        management.completeAutonomousObjective(objectiveId, runnerId, lease.token(), clock.instant());
    }

    private AutonomousObjectiveWork recoverReadOnlyFailure(String objectiveId, int graphVersion,
                                                            NodeExecutionOutcome outcome, ManagementLease lease) {
        List<String> unsafe = coordination.reconcileInterrupted(objectiveId, graphVersion, clock.instant());
        if (!unsafe.isEmpty()) {
            throw new IllegalStateException("read-only recovery encountered unsafe mutating dispatches:" + unsafe);
        }
        String owner = management.get(objectiveId).ownerWorkerId();
        String typedFailure = "routine-read-only-dispatch-failure:" + outcome.stepId() + ":" + outcome.failure();
        management.markBlocked(objectiveId, owner, typedFailure, clock.instant());
        management.recoverLocally(objectiveId, owner,
                "bounded-read-only-retry:step=" + outcome.stepId()
                        + ":failed-attempt=" + outcome.dispatchAttempt()
                        + ":next-attempt=" + (outcome.dispatchAttempt() + 1),
                clock.instant());
        AutonomousObjectiveWork recovered = management.beginExecution(
                objectiveId, runnerId, lease.token(), clock.instant());
        LOG.warn("autonomy_recovery_scheduled objective_id={} step_id={} failed_attempt={} next_attempt={} failure={}",
                objectiveId, outcome.stepId(), outcome.dispatchAttempt(), outcome.dispatchAttempt() + 1,
                outcome.failure());
        return recovered;
    }

    private AutonomousObjectiveWork reconcileSucceededNodes(String objectiveId, int graphVersion,
                                                              AutonomousObjectiveWork work, ManagementLease lease) {
        DurableWorkGraph graph = coordination.activeGraph(objectiveId).orElseThrow();
        if (graph.graphVersion() != graphVersion) throw new IllegalStateException("graph version changed during reconciliation");
        for (DurableWorkGraph.Node node : graph.nodes().values()) {
            if (node.status() == DurableWorkGraph.NodeStatus.SUCCEEDED
                    && !work.completedStepIds().contains(node.spec().stepId())) {
                work = management.recordStepCompleted(objectiveId, runnerId, lease.token(),
                        node.spec().stepId(), node.evidenceReferences(), clock.instant());
            }
        }
        return work;
    }

    private NodeExecutionOutcome executeNode(String objectiveId, int graphVersion, int plannedAttempt,
                                             AutonomousObjectiveWork work, ExecutionWorkSpec step,
                                             AutonomousExecutionCapability capability, String schedulerDecisionId) {
        String expectedDispatchId = objectiveId + ":graph:" + graphVersion + ":step:"
                + step.stepId() + ":attempt:" + plannedAttempt;
        try {
            if (safety != null) {
                safety.reserveDispatch(objectiveId, expectedDispatchId, plannedAttempt,
                        requireAuthority(capability.authorityReference()), step.consequence(),
                        Math.max(0.000001d, capability.requiredCapacity()), clock.instant());
            }
        } catch (AutonomySafetyService.SafetyGateException denied) {
            return NodeExecutionOutcome.failed(step.stepId(), denied.getMessage(), plannedAttempt);
        }

        DurableDispatch dispatch = coordination.beginDispatch(objectiveId, graphVersion, step.stepId(), clock.instant());
        if (dispatch.attempt() != plannedAttempt || !dispatch.dispatchId().equals(expectedDispatchId)) {
            throw new IllegalStateException("dispatch identity changed after safety reservation");
        }
        try {
            AutonomousExecutionCapability.CapabilityResult result = capability.execute(
                    new AutonomousExecutionCapability.CapabilityRequest(
                            work.humanId(), work.organizationContextId(), objectiveId, step)
                            .withDispatch(dispatch.dispatchId(), dispatch.attempt()));
            if (!result.success()) {
                String failure = "capability-unsuccessful:" + nonBlank(result.summary(), "unspecified");
                if (recoverableReadOnly(step, failure, plannedAttempt)) {
                    return NodeExecutionOutcome.recoverable(step.stepId(), failure, plannedAttempt);
                }
                coordination.failDispatch(dispatch.dispatchId(), failure, clock.instant());
                return NodeExecutionOutcome.failed(step.stepId(), failure, plannedAttempt);
            }
            List<String> evidence = new ArrayList<>(result.evidenceReferences());
            evidence.add("autonomous-step:" + step.stepId()
                    + ":capability=" + step.requiredCapability()
                    + ":work=" + result.workReference()
                    + ":worker=" + result.workerId()
                    + ":dispatch=" + dispatch.dispatchId()
                    + ":idempotency=" + dispatch.idempotencyKey());
            if (schedulerDecisionId != null && !schedulerDecisionId.isBlank()) {
                evidence.add("scheduler-decision:" + schedulerDecisionId);
            }
            coordination.completeDispatch(dispatch.dispatchId(), evidence, clock.instant());
            return NodeExecutionOutcome.succeeded(step.stepId(), result.assignmentReference(), evidence, plannedAttempt);
        } catch (RuntimeException failure) {
            String classified = classify(failure);
            if (recoverableReadOnly(step, classified, plannedAttempt)) {
                return NodeExecutionOutcome.recoverable(step.stepId(), classified, plannedAttempt);
            }
            coordination.failDispatch(dispatch.dispatchId(), classified, clock.instant());
            return NodeExecutionOutcome.failed(step.stepId(), classified, plannedAttempt);
        }
    }

    private static boolean recoverableReadOnly(ExecutionWorkSpec step, String failure, int attempt) {
        if (step.consequence() != ExecutionWorkSpec.Consequence.READ_ONLY
                || attempt >= MAX_READ_ONLY_DISPATCH_ATTEMPTS) return false;
        String value = failure == null ? "" : failure;
        return !value.startsWith("authorization-failure:")
                && !value.startsWith("data-failure:")
                && !value.startsWith("autonomy-safety-gate:");
    }

    private static NodeExecutionOutcome await(Future<NodeExecutionOutcome> future) {
        try { return future.get(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("work dispatch interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("work dispatch failed", cause);
        }
    }

    public List<String> capabilityCatalog() {
        return capabilities.keySet().stream().sorted().toList();
    }

    private void blockIfLeaseActive(String objectiveId, ManagementLease lease, String reason) {
        try {
            management.blockAutonomousObjective(objectiveId, runnerId, lease.token(), reason, clock.instant());
        } catch (RuntimeException staleOrTerminal) {
            LOG.warn("Could not persist autonomous blocker for {}: {}", objectiveId, staleOrTerminal.getMessage());
        }
    }

    private void releaseIfOwned(String objectiveId, ManagementLease lease) {
        try { management.releaseManagementLease(objectiveId, runnerId, lease.token(), clock.instant()); }
        catch (RuntimeException stale) { LOG.debug("Management lease already expired or replaced for {}", objectiveId); }
    }

    private static String classify(RuntimeException failure) {
        String type = failure instanceof SecurityException ? "authorization-failure"
                : failure instanceof IllegalArgumentException ? "data-failure"
                : failure instanceof IllegalStateException ? "logic-or-provider-failure"
                : "runtime-failure";
        String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return type + ":" + detail;
    }

    private static String requireAuthority(String value) {
        if (value == null || value.isBlank()) throw new SecurityException("authority-reference-missing");
        return value.trim();
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @Override public void close() {
        executor.shutdownNow();
        workExecutor.shutdownNow();
    }

    private record NodeExecutionOutcome(String stepId, boolean success, boolean missingCapability,
                                        boolean recoverableReadOnlyFailure, String requiredCapability,
                                        String assignmentReference, List<String> evidenceReferences,
                                        String failure, int dispatchAttempt) {
        static NodeExecutionOutcome succeeded(String stepId, String assignmentReference,
                                              List<String> evidence, int attempt) {
            return new NodeExecutionOutcome(stepId, true, false, false, "",
                    assignmentReference == null ? "" : assignmentReference,
                    List.copyOf(evidence), "", attempt);
        }
        static NodeExecutionOutcome failed(String stepId, String failure, int attempt) {
            return new NodeExecutionOutcome(stepId, false, false, false, "", "", List.of(), failure, attempt);
        }
        static NodeExecutionOutcome recoverable(String stepId, String failure, int attempt) {
            return new NodeExecutionOutcome(stepId, false, false, true, "", "", List.of(), failure, attempt);
        }
        static NodeExecutionOutcome missing(String stepId, String capability) {
            return new NodeExecutionOutcome(stepId, false, true, false, capability, "", List.of(),
                    "missing-capability", 0);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
    private static Duration requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }
}
