package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionPlanProposalService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.observation.ObservationClosureService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 */
public final class AutonomousManagementRunner implements AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AutonomousManagementRunner.class);
    private static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);
    private static final Duration DEFAULT_POLL = Duration.ofSeconds(5);
    private static final int DEFAULT_PARALLELISM = 4;

    private final ManagementAutonomyService management;
    private final ExecutionPlanProposalService planner;
    private final Map<String, AutonomousExecutionCapability> capabilities;
    private final AutonomyCoordinationService coordination;
    private final ObservationClosureService observationClosure;
    private final Clock clock;
    private final String runnerId;
    private final Duration leaseDuration;
    private final Duration pollInterval;
    private final ScheduledExecutorService executor;
    private final ExecutorService workExecutor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final ReentrantLock runLock = new ReentrantLock();

    public AutonomousManagementRunner(ManagementAutonomyService management,
                                      ExecutionPlanProposalService planner,
                                      List<AutonomousExecutionCapability> executionCapabilities,
                                      Clock clock) {
        this(management, planner, executionCapabilities, new AutonomyCoordinationService(), null, clock,
                "management-runner:" + UUID.randomUUID(), DEFAULT_LEASE, DEFAULT_POLL, DEFAULT_PARALLELISM);
    }

    public AutonomousManagementRunner(ManagementAutonomyService management,
                                      ExecutionPlanProposalService planner,
                                      List<AutonomousExecutionCapability> executionCapabilities,
                                      AutonomyCoordinationService coordination,
                                      Clock clock) {
        this(management, planner, executionCapabilities, coordination, null, clock,
                "management-runner:" + UUID.randomUUID(), DEFAULT_LEASE, DEFAULT_POLL, DEFAULT_PARALLELISM);
    }

    public AutonomousManagementRunner(ManagementAutonomyService management,
                                      ExecutionPlanProposalService planner,
                                      List<AutonomousExecutionCapability> executionCapabilities,
                                      AutonomyCoordinationService coordination,
                                      ObservationClosureService observationClosure,
                                      Clock clock) {
        this(management, planner, executionCapabilities, coordination,
                Objects.requireNonNull(observationClosure, "observationClosure"), clock,
                "management-runner:" + UUID.randomUUID(), DEFAULT_LEASE, DEFAULT_POLL, DEFAULT_PARALLELISM);
    }

    AutonomousManagementRunner(ManagementAutonomyService management,
                               ExecutionPlanProposalService planner,
                               List<AutonomousExecutionCapability> executionCapabilities,
                               Clock clock, String runnerId, Duration leaseDuration,
                               Duration pollInterval) {
        this(management, planner, executionCapabilities, new AutonomyCoordinationService(), null, clock,
                runnerId, leaseDuration, pollInterval, DEFAULT_PARALLELISM);
    }

    AutonomousManagementRunner(ManagementAutonomyService management,
                               ExecutionPlanProposalService planner,
                               List<AutonomousExecutionCapability> executionCapabilities,
                               AutonomyCoordinationService coordination,
                               Clock clock, String runnerId, Duration leaseDuration,
                               Duration pollInterval, int parallelism) {
        this(management, planner, executionCapabilities, coordination, null, clock,
                runnerId, leaseDuration, pollInterval, parallelism);
    }

    AutonomousManagementRunner(ManagementAutonomyService management,
                               ExecutionPlanProposalService planner,
                               List<AutonomousExecutionCapability> executionCapabilities,
                               AutonomyCoordinationService coordination,
                               ObservationClosureService observationClosure,
                               Clock clock, String runnerId, Duration leaseDuration,
                               Duration pollInterval, int parallelism) {
        this.management = Objects.requireNonNull(management, "management");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.coordination = Objects.requireNonNull(coordination, "coordination");
        this.observationClosure = observationClosure;
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

            List<Future<NodeExecutionOutcome>> futures = new ArrayList<>();
            int graphVersion = graph.graphVersion();
            for (DurableWorkGraph.Node node : ready) {
                AutonomousExecutionCapability capability = capabilities.get(node.spec().requiredCapability());
                if (capability == null) {
                    futures.add(java.util.concurrent.CompletableFuture.completedFuture(
                            NodeExecutionOutcome.missing(node.spec().stepId(), node.spec().requiredCapability())));
                } else {
                    AutonomousObjectiveWork dispatchContext = work;
                    futures.add(workExecutor.submit(() -> executeNode(
                            objectiveId, graphVersion, dispatchContext, node.spec(), capability)));
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
                    management.blockAutonomousObjective(objectiveId, runnerId, lease.token(),
                            "worker-execution-failure:" + outcome.stepId() + ":" + outcome.failure(), clock.instant());
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

    private NodeExecutionOutcome executeNode(String objectiveId, int graphVersion, AutonomousObjectiveWork work,
                                             ExecutionWorkSpec step, AutonomousExecutionCapability capability) {
        DurableDispatch dispatch = coordination.beginDispatch(objectiveId, graphVersion, step.stepId(), clock.instant());
        try {
            AutonomousExecutionCapability.CapabilityResult result = capability.execute(
                    new AutonomousExecutionCapability.CapabilityRequest(
                            work.humanId(), work.organizationContextId(), objectiveId, step)
                            .withDispatch(dispatch.dispatchId(), dispatch.attempt()));
            if (!result.success()) {
                coordination.failDispatch(dispatch.dispatchId(), result.summary(), clock.instant());
                return NodeExecutionOutcome.failed(step.stepId(), result.summary());
            }
            List<String> evidence = new ArrayList<>(result.evidenceReferences());
            evidence.add("autonomous-step:" + step.stepId()
                    + ":capability=" + step.requiredCapability()
                    + ":work=" + result.workReference()
                    + ":worker=" + result.workerId()
                    + ":dispatch=" + dispatch.dispatchId()
                    + ":idempotency=" + dispatch.idempotencyKey());
            coordination.completeDispatch(dispatch.dispatchId(), evidence, clock.instant());
            return NodeExecutionOutcome.succeeded(step.stepId(), result.assignmentReference(), evidence);
        } catch (RuntimeException failure) {
            coordination.failDispatch(dispatch.dispatchId(), classify(failure), clock.instant());
            return NodeExecutionOutcome.failed(step.stepId(), classify(failure));
        }
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

    @Override public void close() {
        executor.shutdownNow();
        workExecutor.shutdownNow();
    }

    private record NodeExecutionOutcome(String stepId, boolean success, boolean missingCapability,
                                        String requiredCapability, String assignmentReference,
                                        List<String> evidenceReferences, String failure) {
        static NodeExecutionOutcome succeeded(String stepId, String assignmentReference, List<String> evidence) {
            return new NodeExecutionOutcome(stepId, true, false, "",
                    assignmentReference == null ? "" : assignmentReference, List.copyOf(evidence), "");
        }
        static NodeExecutionOutcome failed(String stepId, String failure) {
            return new NodeExecutionOutcome(stepId, false, false, "", "", List.of(), failure);
        }
        static NodeExecutionOutcome missing(String stepId, String capability) {
            return new NodeExecutionOutcome(stepId, false, true, capability, "", List.of(), "missing-capability");
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
