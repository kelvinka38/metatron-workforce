package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionPlanProposalService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.observation.ObservationClosureService;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.actor.WorkerActorAssignmentConsumer;

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
    private static final Duration DEFAULT_NODE_EXECUTION_TIMEOUT = Duration.ofMinutes(30);
    private static final int DEFAULT_PARALLELISM = 4;
    private static final int MAX_READ_ONLY_DISPATCH_ATTEMPTS = 3;
    private static final int MAX_AUTONOMOUS_REPLANS = 1;

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
    private volatile Duration nodeExecutionTimeout = DEFAULT_NODE_EXECUTION_TIMEOUT;
    /** Optional production bridge used to close canonical Assignments only after Observation PASS. */
    private volatile WorkforceCoreService workforceCore;
    private volatile WorkerActorAssignmentConsumer assignmentConsumer;

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

    /** Binds the canonical Assignment lifecycle to this runner's independent Observation boundary. */
    public AutonomousManagementRunner configureAssignmentConsumer(WorkerActorAssignmentConsumer consumer) {
        if (started.get()) throw new IllegalStateException("assignment consumer must be configured before runner start");
        this.assignmentConsumer = Objects.requireNonNull(consumer, "consumer");
        return this;
    }

    public AutonomousManagementRunner configureAssignmentLifecycle(WorkforceCoreService core) {
        if (started.get()) throw new IllegalStateException("assignment lifecycle must be configured before runner start");
        this.workforceCore = Objects.requireNonNull(core, "core");
        return this;
    }

    /** Bounds a single node dispatch; see {@link #await(PendingNodeExecution)} for why this exists. */
    public AutonomousManagementRunner configureNodeExecutionTimeout(Duration timeout) {
        if (started.get()) throw new IllegalStateException("node execution timeout must be configured before runner start");
        this.nodeExecutionTimeout = requirePositive(timeout, "nodeExecutionTimeout");
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
            LOG.info("autonomy_plan_recorded objective_id={} steps={}", objectiveId,
                    proposed.stream().map(step -> step.stepId() + "=" + step.requiredCapability()
                            + "/" + step.consequence()).toList());
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
                    boolean executionCapabilityGapOnly = !decision.deferredReasons().isEmpty()
                            && decision.deferredReasons().values().stream()
                            .allMatch(reason -> reason.startsWith("capability-unavailable:"));
                    if (executionCapabilityGapOnly) {
                        Map.Entry<String, String> firstGap = decision.deferredReasons().entrySet().stream()
                                .sorted(Map.Entry.comparingByKey()).findFirst().orElseThrow();
                        handleCapabilityPlanGap(objectiveId, firstGap.getKey(),
                                firstGap.getValue().substring("capability-unavailable:".length()), lease);
                    } else if (!temporaryCapacityOnly) {
                        management.blockAutonomousObjective(objectiveId, runnerId, lease.token(),
                                "scheduler-admission-blocked:" + reasons, clock.instant());
                    }
                    return;
                }
            }

            List<PendingNodeExecution> dispatched = new ArrayList<>();
            int graphVersion = graph.graphVersion();
            String schedulerRef = schedulingDecisionId;
            for (DurableWorkGraph.Node node : ready) {
                AutonomousExecutionCapability capability = capabilities.get(node.spec().requiredCapability());
                if (capability == null) {
                    dispatched.add(new PendingNodeExecution(node.spec(), node.attempt(),
                            java.util.concurrent.CompletableFuture.completedFuture(
                                    NodeExecutionOutcome.missing(node.spec().stepId(), node.spec().requiredCapability()))));
                } else {
                    AutonomousObjectiveWork dispatchContext = work;
                    int plannedAttempt = node.attempt() + 1;
                    dispatched.add(new PendingNodeExecution(node.spec(), plannedAttempt, workExecutor.submit(() -> executeNode(
                            objectiveId, graphVersion, plannedAttempt, dispatchContext, node.spec(), capability,
                            schedulerRef))));
                }
            }

            boolean stop = false;
            for (PendingNodeExecution pending : dispatched) {
                NodeExecutionOutcome outcome = await(pending);
                if (outcome.missingCapability()) {
                    handleCapabilityPlanGap(objectiveId, outcome.stepId(), outcome.requiredCapability(), lease);
                    stop = true;
                    continue;
                }
                if (!outcome.success()) {
                    if (outcome.recoverableReadOnlyFailure()) {
                        work = recoverReadOnlyFailure(objectiveId, graphVersion, outcome, lease);
                        continue;
                    }
                    if (outcome.autonomousReplanEligible()) {
                        handleAutonomousReplan(objectiveId, outcome, lease);
                        stop = true;
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
        boolean assignmentsClosed = completeAssignmentsAfterObservation(objectiveId, work.evidenceReferences());
        if (!assignmentsClosed) {
            LOG.info("autonomy_objective_awaiting_release_evidence objective_id={}", objectiveId);
            return;
        }
        management.completeAutonomousObjective(objectiveId, runnerId, lease.token(), clock.instant());
        AutonomousObjectiveWork completed = management.findAutonomousWork(objectiveId).orElseThrow();
        LOG.info("autonomy_objective_completed objective_id={} completed_steps={} planned_steps={} evidence_count={}",
                objectiveId, completed.completedStepIds().size(), completed.plannedWork().size(),
                completed.evidenceReferences().size());
    }

    private boolean completeAssignmentsAfterObservation(String objectiveId, List<String> evidenceReferences) {
        WorkforceCoreService core = workforceCore;
        if (core == null) return true;
        boolean hasEvidence = evidenceReferences != null && evidenceReferences.stream()
                .filter(Objects::nonNull).map(String::trim).anyMatch(value -> !value.isBlank());
        boolean allClosed = true;
        for (WorkforceCoreService.Assignment assignment : core.allAssignments()) {
            if (!objectiveId.equals(assignment.objectiveRef())) continue;
            if (assignment.status() == WorkforceCoreService.AssignmentStatus.ACTIVE
                    || assignment.status() == WorkforceCoreService.AssignmentStatus.PLANNED) {
                if (!hasEvidence) {
                    core.transitionAssignment(assignment.assignmentId(), WorkforceCoreService.AssignmentStatus.BLOCKED);
                    throw new IllegalStateException("reason=evidence_missing");
                }
                try {
                    core.transitionAssignment(assignment.assignmentId(), WorkforceCoreService.AssignmentStatus.COMPLETED);
                } catch (com.metatron.workforce.core.CompletionEvidenceRequiredException awaitingReleaseEvidence) {
                    // Execution succeeded; this Assignment's declared CompletionPolicy requires release
                    // evidence (PR/merge/deploy/verify) that does not exist yet. This is NOT an execution
                    // failure: the Assignment correctly stays ACTIVE/PLANNED, and Objective completion is
                    // deferred rather than blocked. A later management pass (normal poll, or an explicit
                    // wake() once release evidence is recorded) retries this same idempotent path.
                    allClosed = false;
                }
            }
        }
        return allClosed;
    }


    private void handleCapabilityPlanGap(String objectiveId, String stepId, String requiredCapability,
                                         ManagementLease lease) {
        String failure = "missing_capability_id=" + requiredCapability
                + ";registry_result=NOT_REGISTERED"
                + ";planner_contract_defect=true"
                + ";step_id=" + stepId
                + ";registered_capabilities=" + capabilityCatalog();
        management.blockAutonomousObjective(objectiveId, runnerId, lease.token(), failure, clock.instant());
        LOG.error("autonomy_capability_registry_defect objective_id={} step_id={} missing_capability_id={} registry_result=NOT_REGISTERED planner_contract_defect=true registered_capabilities={}",
                objectiveId, stepId, requiredCapability, capabilityCatalog());
    }

    private void handleAutonomousReplan(String objectiveId, NodeExecutionOutcome outcome,
                                        ManagementLease lease) {
        long priorReplans = management.history(objectiveId).stream()
                .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.REPLAN_REQUESTED)
                .count();
        String failure = "bounded-autonomous-replan-required:step=" + outcome.stepId()
                + ":attempt=" + outcome.dispatchAttempt() + ":failure=" + outcome.failure();
        management.blockAutonomousObjective(
                objectiveId, runnerId, lease.token(), failure, clock.instant());
        String owner = management.get(objectiveId).ownerWorkerId();
        if (priorReplans < MAX_AUTONOMOUS_REPLANS) {
            management.requestReplan(objectiveId, owner, failure, clock.instant());
            LOG.warn("autonomy_replan_requested objective_id={} step_id={} exhausted_attempt={} prior_replans={} failure={}",
                    objectiveId, outcome.stepId(), outcome.dispatchAttempt(), priorReplans, outcome.failure());
            return;
        }
        management.escalate(objectiveId, owner,
                "bounded-autonomous-recovery-exhausted:replans=" + priorReplans
                        + ":step=" + outcome.stepId() + ":failure=" + outcome.failure(),
                clock.instant());
        LOG.error("autonomy_recovery_escalated objective_id={} step_id={} exhausted_attempt={} replans={} failure={}",
                objectiveId, outcome.stepId(), outcome.dispatchAttempt(), priorReplans, outcome.failure());
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
            return NodeExecutionOutcome.failed(step.stepId(), denied.getMessage(), plannedAttempt, false);
        }

        DurableDispatch dispatch = coordination.beginDispatch(objectiveId, graphVersion, step.stepId(), clock.instant());
        if (dispatch.attempt() != plannedAttempt || !dispatch.dispatchId().equals(expectedDispatchId)) {
            throw new IllegalStateException("dispatch identity changed after safety reservation");
        }
        try {
            AutonomousExecutionCapability.CapabilityRequest capabilityRequest =
                    new AutonomousExecutionCapability.CapabilityRequest(
                            work.humanId(), work.organizationContextId(), objectiveId, step)
                            .withDispatch(dispatch.dispatchId(), dispatch.attempt());
            AutonomousExecutionCapability.CapabilityResult result;
            if (capability instanceof GovernedAutonomousExecutionCapability governed && assignmentConsumer != null) {
                WorkforceCoreService.Assignment durableAssignment = governed.prepareAssignment(capabilityRequest);
                // Truthful assignment observability: link the real durable Assignment to the Management
                // Objective as soon as it exists on this path too, before execution outcome is known (see
                // GovernedAutonomousExecutionCapability.onAssignmentCreated() for the equivalent fix on the
                // direct execute() path, which is what production's SafetyGoverned-wrapped composition
                // actually dispatches through). addAssignmentReference is idempotent, so this is safe even
                // when the listener also fires for the same Assignment.
                String owner = management.get(objectiveId).ownerWorkerId();
                management.addAssignmentReference(objectiveId, owner, durableAssignment.assignmentId(), clock.instant());
                try {
                    result = assignmentConsumer.submit(durableAssignment,
                            assignment -> governed.executeAssigned(capabilityRequest, assignment)).get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("worker assignment consumer interrupted", interrupted);
                } catch (java.util.concurrent.ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    throw new IllegalStateException("worker assignment consumer failed", cause);
                }
            } else {
                result = capability.execute(capabilityRequest);
            }
            if (result.success() && result.evidenceReferences().stream()
                    .filter(Objects::nonNull).map(String::trim).noneMatch(value -> !value.isBlank())) {
                String failure = "reason=evidence_missing";
                WorkforceCoreService core = workforceCore;
                if (core != null && result.assignmentReference() != null && !result.assignmentReference().isBlank()) {
                    core.allAssignments().stream()
                            .filter(assignment -> assignment.assignmentId().equals(result.assignmentReference()))
                            .filter(assignment -> assignment.status() == WorkforceCoreService.AssignmentStatus.ACTIVE
                                    || assignment.status() == WorkforceCoreService.AssignmentStatus.PLANNED)
                            .findFirst()
                            .ifPresent(assignment -> core.transitionAssignment(
                                    assignment.assignmentId(), WorkforceCoreService.AssignmentStatus.BLOCKED));
                }
                coordination.failDispatch(dispatch.dispatchId(), failure, clock.instant());
                return NodeExecutionOutcome.failed(step.stepId(), failure, plannedAttempt, false);
            }
            if (!result.success()) {
                String failure = "capability-unsuccessful:" + nonBlank(result.summary(), "unspecified");
                LOG.warn("autonomy_step_execution_failed objective_id={} step_id={} capability={} worker={} assignment={} dispatch={} attempt={} failure={}",
                        objectiveId, step.stepId(), step.requiredCapability(), result.workerId(),
                        result.assignmentReference(), dispatch.dispatchId(), plannedAttempt, failure);
                if (recoverableReadOnly(step, failure, plannedAttempt)) {
                    return NodeExecutionOutcome.recoverable(step.stepId(), failure, plannedAttempt);
                }
                coordination.failDispatch(dispatch.dispatchId(), failure, clock.instant());
                return NodeExecutionOutcome.failed(step.stepId(), failure, plannedAttempt,
                        autonomousReplanEligible(step, failure));
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
            LOG.info("autonomy_step_execution_succeeded objective_id={} step_id={} capability={} worker={} assignment={} dispatch={} attempt={} evidence_count={}",
                    objectiveId, step.stepId(), step.requiredCapability(), result.workerId(),
                    result.assignmentReference(), dispatch.dispatchId(), plannedAttempt, evidence.size());
            return NodeExecutionOutcome.succeeded(step.stepId(), result.assignmentReference(), evidence, plannedAttempt);
        } catch (RuntimeException failure) {
            String classified = classify(failure);
            LOG.warn("autonomy_step_execution_exception objective_id={} step_id={} capability={} dispatch={} attempt={} failure={}",
                    objectiveId, step.stepId(), step.requiredCapability(), dispatch.dispatchId(), plannedAttempt, classified);
            if (recoverableReadOnly(step, classified, plannedAttempt)) {
                return NodeExecutionOutcome.recoverable(step.stepId(), classified, plannedAttempt);
            }
            coordination.failDispatch(dispatch.dispatchId(), classified, clock.instant());
            return NodeExecutionOutcome.failed(step.stepId(), classified, plannedAttempt,
                    autonomousReplanEligible(step, classified));
        }
    }

    private static boolean recoverableReadOnly(ExecutionWorkSpec step, String failure, int attempt) {
        return step.consequence() == ExecutionWorkSpec.Consequence.READ_ONLY
                && attempt < MAX_READ_ONLY_DISPATCH_ATTEMPTS
                && autonomousReplanEligible(step, failure);
    }

    private static boolean autonomousReplanEligible(ExecutionWorkSpec step, String failure) {
        return AutonomousRecoveryPolicy.autonomousReplanEligible(step, failure);
    }

    /**
     * Bounds every node dispatch so a single stuck capability call (network stall, deadlocked
     * external process) can never freeze this runner's single-threaded pass forever. Before this
     * bound existed, an unbounded future.get() here meant one hung dispatch silently starved
     * every objective in the system indefinitely, since runOnce()'s exclusive lock is held for
     * the whole pass -- observed in production as a total, CPU-idle stall with zero further log
     * output until an operator manually restarted the process.
     *
     * A READ_ONLY timeout is treated like any other recoverable read-only failure (safe to
     * retry: reconcileInterrupted() already resets READ_ONLY DISPATCHED nodes to PENDING). A
     * MUTATING timeout is never auto-replanned, since the abandoned execution may still be
     * running in the background and a fresh replan could dispatch a duplicate mutation; it
     * surfaces as a governed block instead, exactly like any other unsafe-interrupted mutating
     * dispatch, so reconcileInterrupted() can resolve it once the objective is recovered.
     */
    private NodeExecutionOutcome await(PendingNodeExecution pending) {
        try { return pending.future().get(nodeExecutionTimeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("work dispatch interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("work dispatch failed", cause);
        } catch (java.util.concurrent.TimeoutException timedOut) {
            pending.future().cancel(true);
            ExecutionWorkSpec step = pending.step();
            String failure = "node-execution-timeout:exceeded-" + nodeExecutionTimeout;
            LOG.error("autonomy_step_execution_timeout step_id={} capability={} attempt={} timeout={}",
                    step.stepId(), step.requiredCapability(), pending.attempt(), nodeExecutionTimeout);
            if (recoverableReadOnly(step, failure, pending.attempt())) {
                return NodeExecutionOutcome.recoverable(step.stepId(), failure, pending.attempt());
            }
            return NodeExecutionOutcome.failed(step.stepId(), failure, pending.attempt(), false);
        }
    }

    private record PendingNodeExecution(ExecutionWorkSpec step, int attempt, Future<NodeExecutionOutcome> future) {}

    public List<String> capabilityCatalog() {
        return capabilities.keySet().stream().sorted().toList();
    }

    private void blockIfLeaseActive(String objectiveId, ManagementLease lease, String reason) {
        try {
            if (management.get(objectiveId).status() == ManagementObjective.Status.BLOCKED) return;
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
                                        boolean recoverableReadOnlyFailure, boolean autonomousReplanEligible,
                                        String requiredCapability, String assignmentReference,
                                        List<String> evidenceReferences, String failure, int dispatchAttempt) {
        static NodeExecutionOutcome succeeded(String stepId, String assignmentReference,
                                              List<String> evidence, int attempt) {
            return new NodeExecutionOutcome(stepId, true, false, false, false, "",
                    assignmentReference == null ? "" : assignmentReference,
                    List.copyOf(evidence), "", attempt);
        }
        static NodeExecutionOutcome failed(String stepId, String failure, int attempt,
                                           boolean autonomousReplanEligible) {
            return new NodeExecutionOutcome(stepId, false, false, false, autonomousReplanEligible,
                    "", "", List.of(), failure, attempt);
        }
        static NodeExecutionOutcome recoverable(String stepId, String failure, int attempt) {
            return new NodeExecutionOutcome(stepId, false, false, true, false,
                    "", "", List.of(), failure, attempt);
        }
        static NodeExecutionOutcome missing(String stepId, String capability) {
            return new NodeExecutionOutcome(stepId, false, true, false, false,
                    capability, "", List.of(), "missing-capability", 0);
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
