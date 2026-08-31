package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionPlanProposalService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Restartable Workforce management loop. The runner owns operational progression after durable
 * Objective acceptance; callers and channels never have to keep their interaction alive.
 */
public final class AutonomousManagementRunner implements AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AutonomousManagementRunner.class);
    private static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);
    private static final Duration DEFAULT_POLL = Duration.ofSeconds(5);

    private final ManagementAutonomyService management;
    private final ExecutionPlanProposalService planner;
    private final Map<String, AutonomousExecutionCapability> capabilities;
    private final Clock clock;
    private final String runnerId;
    private final Duration leaseDuration;
    private final Duration pollInterval;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final ReentrantLock runLock = new ReentrantLock();

    public AutonomousManagementRunner(ManagementAutonomyService management,
                                      ExecutionPlanProposalService planner,
                                      List<AutonomousExecutionCapability> executionCapabilities,
                                      Clock clock) {
        this(management, planner, executionCapabilities, clock,
                "management-runner:" + UUID.randomUUID(), DEFAULT_LEASE, DEFAULT_POLL);
    }

    AutonomousManagementRunner(ManagementAutonomyService management,
                               ExecutionPlanProposalService planner,
                               List<AutonomousExecutionCapability> executionCapabilities,
                               Clock clock, String runnerId, Duration leaseDuration,
                               Duration pollInterval) {
        this.management = Objects.requireNonNull(management, "management");
        this.planner = Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(executionCapabilities, "executionCapabilities");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runnerId = requireText(runnerId, "runnerId");
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        this.pollInterval = requirePositive(pollInterval, "pollInterval");
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
    }

    /** Starts durable polling. Safe to call more than once. */
    public void start() {
        if (started.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(this::runSafely, 0,
                    pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Hints that newly accepted work is available; acceptance never waits for this execution. */
    public void wake() {
        if (!started.get()) return;
        executor.execute(this::runSafely);
    }

    /** One deterministic management pass, exposed for recovery/acceptance testing. */
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
        try {
            runOnce();
        } catch (RuntimeException failure) {
            LOG.error("Autonomous management pass failed", failure);
        }
    }

    private void processWithLease(String objectiveId) {
        Instant now = clock.instant();
        ManagementLease lease = management.acquireManagementLease(
                objectiveId, runnerId, leaseDuration, now).orElse(null);
        if (lease == null) return;
        try {
            process(objectiveId, lease);
        } catch (RuntimeException failure) {
            blockIfLeaseActive(objectiveId, lease, classify(failure));
        } finally {
            releaseIfOwned(objectiveId, lease);
        }
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
            work = management.recordPlan(
                    objectiveId, runnerId, lease.token(), proposed, clock.instant());
        }

        if (work.status() != AutonomousObjectiveWork.Status.READY
                && work.status() != AutonomousObjectiveWork.Status.EXECUTING) return;

        work = management.beginExecution(objectiveId, runnerId, lease.token(), clock.instant());
        while (work.completedStepIds().size() < work.plannedWork().size()) {
            List<ExecutionWorkSpec> ready = readyWork(work);
            if (ready.isEmpty()) {
                management.blockAutonomousObjective(objectiveId, runnerId, lease.token(),
                        "dependency-failure:no-runnable-work", clock.instant());
                return;
            }
            for (ExecutionWorkSpec step : ready) {
                if (management.findAutonomousWork(objectiveId).orElseThrow().completedStepIds().contains(step.stepId())) {
                    continue;
                }
                if (!executeStep(objectiveId, work, step, lease)) return;
                work = management.findAutonomousWork(objectiveId).orElseThrow();
            }
        }
        management.completeAutonomousObjective(objectiveId, runnerId, lease.token(), clock.instant());
    }

    private boolean executeStep(String objectiveId, AutonomousObjectiveWork work,
                                ExecutionWorkSpec step, ManagementLease lease) {
        AutonomousExecutionCapability capability = capabilities.get(step.requiredCapability());
        String owner = management.get(objectiveId).ownerWorkerId();
        if (capability == null) {
            management.assessCapacity(objectiveId, owner, step.requiredCapability(), 1.0, 0.0, clock.instant());
            management.blockAutonomousObjective(objectiveId, runnerId, lease.token(),
                    "staffing-required:" + step.requiredCapability(), clock.instant());
            return false;
        }

        management.assessCapacity(objectiveId, owner, step.requiredCapability(), 1.0, 1.0, clock.instant());
        AutonomousExecutionCapability.CapabilityResult result = capability.execute(
                new AutonomousExecutionCapability.CapabilityRequest(
                        work.humanId(), work.organizationContextId(), objectiveId, step));
        if (!result.success()) {
            management.blockAutonomousObjective(objectiveId, runnerId, lease.token(),
                    "worker-execution-failure:" + step.stepId() + ":" + result.summary(), clock.instant());
            return false;
        }
        if (!result.assignmentReference().isBlank()) {
            management.addAssignmentReference(objectiveId, owner,
                    result.assignmentReference(), clock.instant());
        }
        List<String> evidence = new ArrayList<>(result.evidenceReferences());
        evidence.add("autonomous-step:" + step.stepId()
                + ":capability=" + step.requiredCapability()
                + ":work=" + result.workReference()
                + ":worker=" + result.workerId());
        management.recordStepCompleted(objectiveId, runnerId, lease.token(),
                step.stepId(), evidence, clock.instant());
        return true;
    }

    private List<ExecutionWorkSpec> readyWork(AutonomousObjectiveWork work) {
        return work.plannedWork().stream()
                .filter(step -> !work.completedStepIds().contains(step.stepId()))
                .filter(step -> work.completedStepIds().containsAll(step.dependsOn()))
                .toList();
    }

    public List<String> capabilityCatalog() {
        return capabilities.keySet().stream().sorted().toList();
    }

    private void blockIfLeaseActive(String objectiveId, ManagementLease lease, String reason) {
        try {
            management.blockAutonomousObjective(
                    objectiveId, runnerId, lease.token(), reason, clock.instant());
        } catch (RuntimeException staleOrTerminal) {
            LOG.warn("Could not persist autonomous blocker for {}: {}", objectiveId,
                    staleOrTerminal.getMessage());
        }
    }

    private void releaseIfOwned(String objectiveId, ManagementLease lease) {
        try {
            management.releaseManagementLease(objectiveId, runnerId, lease.token(), clock.instant());
        } catch (RuntimeException stale) {
            LOG.debug("Management lease already expired or replaced for {}", objectiveId);
        }
    }

    private static String classify(RuntimeException failure) {
        String type = failure instanceof SecurityException ? "authorization-failure"
                : failure instanceof IllegalArgumentException ? "data-failure"
                : failure instanceof IllegalStateException ? "logic-or-provider-failure"
                : "runtime-failure";
        String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return type + ":" + detail;
    }

    @Override
    public void close() {
        executor.shutdownNow();
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
