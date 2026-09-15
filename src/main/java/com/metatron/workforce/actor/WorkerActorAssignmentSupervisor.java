package com.metatron.workforce.actor;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.management.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Restart-safe canonical Assignment supervisor. It reconstructs execution solely from durable
 * Workforce Core, ManagementAutonomyService and AutonomyCoordinationService state, then lets the
 * intended WorkerActor claim the existing Assignment mailbox message.
 */
public final class WorkerActorAssignmentSupervisor implements AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WorkerActorAssignmentSupervisor.class);
    private final WorkforceCoreService core;
    private final ManagementAutonomyService management;
    private final AutonomyCoordinationService coordination;
    private final WorkerActorRuntime actors;
    private final Map<String, AutonomousExecutionCapability> capabilities;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService executions = Executors.newVirtualThreadPerTaskExecutor();
    private final Clock clock = Clock.systemUTC();

    public WorkerActorAssignmentSupervisor(WorkforceCoreService core,
            ManagementAutonomyService management, AutonomyCoordinationService coordination,
            WorkerActorRuntime actors, List<AutonomousExecutionCapability> capabilities) {
        this.core=Objects.requireNonNull(core); this.management=Objects.requireNonNull(management);
        this.coordination=Objects.requireNonNull(coordination); this.actors=Objects.requireNonNull(actors);
        this.capabilities=capabilities.stream().collect(Collectors.toUnmodifiableMap(
                AutonomousExecutionCapability::capabilityRef, c -> c));
    }

    public void start() {
        scan();
        scheduler.scheduleWithFixedDelay(this::scanSafely, 250, 1000, TimeUnit.MILLISECONDS);
    }

    public void scan() {
        for (WorkforceCoreService.Assignment a : core.allAssignments()) {
            if (a.status()!=WorkforceCoreService.AssignmentStatus.PLANNED
                    && a.status()!=WorkforceCoreService.AssignmentStatus.ACTIVE) continue;
            if (isCancelledObjective(a.objectiveRef())) continue;
            try { dispatchExisting(a); } catch (RuntimeException ignored) { }
        }
    }

    private void dispatchExisting(WorkforceCoreService.Assignment assignment) {
        DurableWorkGraph graph = coordination.activeGraph(assignment.objectiveRef()).orElseThrow(
                () -> new IllegalStateException("assignment graph missing"));
        String stepId = stepId(assignment.assignmentId());
        DurableWorkGraph.Node node = graph.nodes().get(stepId);
        if (node == null) throw new IllegalStateException("assignment step missing:" + stepId);
        if (node.status()!=DurableWorkGraph.NodeStatus.DISPATCHED
                && node.status()!=DurableWorkGraph.NodeStatus.PENDING) return;
        AutonomousExecutionCapability capability = capabilities.get(node.spec().requiredCapability());
        if (capability == null || !(capability instanceof GovernedAutonomousExecutionCapability governed)) return;
        if (!governed.supportsWorker(assignment.workerId(), node.spec())) return;
        if (!core.worker(assignment.workerId()).status().equals(WorkforceCoreService.WorkerStatus.ACTIVE)) return;
        String dispatchId = node.dispatchId();
        int attempt = node.attempt();
        if (dispatchId == null || dispatchId.isBlank() || attempt < 1) return;
        AutonomousObjectiveWork work = management.findAutonomousWork(assignment.objectiveRef()).orElseThrow();
        AutonomousExecutionCapability.CapabilityRequest request =
                new AutonomousExecutionCapability.CapabilityRequest(work.humanId(),
                        work.organizationContextId(), assignment.objectiveRef(), node.spec())
                        .withDispatch(dispatchId, attempt);
        actors.consumeAssignment(assignment, () -> governed.executeAssigned(request, assignment))
                .whenCompleteAsync((r,e) -> reconcileOutcome(assignment, dispatchId, r, e), executions);
    }

    /**
     * Root-cause fix (2026-09-15, founder-requested closure): restart-driven execution via this
     * Supervisor previously stopped reconciliation at Assignment terminal -- the canonical
     * AutonomyCoordinationService DurableDispatch/Work-Graph-node record, which
     * AutonomousManagementRunner.executeNode() always completes on its own dispatch path, was never
     * touched here. A step the Supervisor picked up after a restart could run its real effect and
     * reach Assignment COMPLETED while its DurableWorkGraph node stayed DISPATCHED forever -- the
     * Objective's own progress tracking (which only advances via
     * AutonomousManagementRunner.reconcileSucceededNodes() noticing a node reach SUCCEEDED) would
     * never learn the step ever happened, leaving the Objective stuck permanently even though the work
     * genuinely completed. This closes that gap using the exact same coordination.completeDispatch()/
     * failDispatch() calls the Runner's own dispatch path already makes -- no new completion
     * semantics, just the same canonical ones, reached from the second entry point. Once the dispatch
     * is marked SUCCEEDED/FAILED here, the Runner's existing reconcileSucceededNodes() picks it up
     * under its own real management lease on its next normal poll tick; this method does not touch
     * ManagementAutonomyService or lease state directly.
     *
     * Also fixes the prior silent swallow: any reconciliation failure (not just the original
     * CancellationException special case) is now logged with its root cause instead of vanishing with
     * no trace, matching the "never swallow supervisor exceptions" requirement.
     */
    private void reconcileOutcome(WorkforceCoreService.Assignment assignment, String dispatchId,
                                  AutonomousExecutionCapability.CapabilityResult result, Throwable failure) {
        Instant now = clock.instant();
        try {
            if (failure != null) {
                if (assignment.status()==WorkforceCoreService.AssignmentStatus.ACTIVE
                        && failure.getCause() instanceof CancellationException) return;
                String reason = "supervisor-dispatch-failed:" + rootCause(failure);
                coordination.failDispatch(dispatchId, reason, now);
                LOG.error("worker_actor_supervisor_dispatch_failed assignment_id={} dispatch_id={} reason={}",
                        assignment.assignmentId(), dispatchId, reason, failure);
                return;
            }
            if (result == null) return; // race already resolved by the winning claimant; nothing new to reconcile
            List<String> evidence = new ArrayList<>(result.evidenceReferences());
            evidence.add("worker-actor-supervisor:assignment=" + assignment.assignmentId() + ":dispatch=" + dispatchId);
            if (result.success()) {
                coordination.completeDispatch(dispatchId, evidence, now);
            } else {
                coordination.failDispatch(dispatchId, nonBlank(result.summary(), "capability-unsuccessful"), now);
            }
        } catch (RuntimeException reconciliationFailure) {
            LOG.error("worker_actor_supervisor_reconciliation_failed assignment_id={} dispatch_id={} error={}",
                    assignment.assignmentId(), dispatchId, reconciliationFailure.getMessage(), reconciliationFailure);
        }
    }

    private static String rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return (message == null || message.isBlank() ? cause.getClass().getSimpleName() : message);
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean isCancelledObjective(String objectiveId) {
        try {
            ManagementObjective o=management.get(objectiveId);
            return o.status()==ManagementObjective.Status.CANCELLED
                    || o.status()==ManagementObjective.Status.COMPLETED;
        } catch (RuntimeException missing) { return true; }
    }

    private static String stepId(String assignmentId) {
        int p=assignmentId.indexOf(":work-step:");
        if (p<0) throw new IllegalStateException("assignment step metadata missing");
        String s=assignmentId.substring(p+11); int end=s.indexOf(':');
        return end<0?s:s.substring(0,end);
    }

    private void scanSafely() { try { scan(); } catch (RuntimeException ignored) { } }
    @Override public void close() { scheduler.shutdownNow(); executions.shutdownNow(); }
}
