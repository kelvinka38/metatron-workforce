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
                .whenCompleteAsync((r,e) -> { if (e != null) reconcileFailure(assignment, e); }, executions);
    }

    private void reconcileFailure(WorkforceCoreService.Assignment a, Throwable failure) {
        if (a.status()==WorkforceCoreService.AssignmentStatus.ACTIVE
                && failure != null && failure.getCause() instanceof CancellationException) return;
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
