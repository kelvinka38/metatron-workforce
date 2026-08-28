package com.metatron.workforce.management;

import com.metatron.workforce.phase3.ActorRef;
import com.metatron.workforce.phase3.ExecutionHandoffRequest;
import com.metatron.workforce.phase3.WorkQueueItem;
import com.metatron.workforce.phase3.WorkQueueService;
import com.metatron.workforce.phase6.ApprovalDecision;
import com.metatron.workforce.phase6.AuthorizationRequest;
import com.metatron.workforce.phase6.AuthorizationService;
import com.metatron.workforce.phase6.ExecutionRecord;
import com.metatron.workforce.phase6.ExecutionService;
import com.metatron.workforce.phase6.WorkProposal;
import com.metatron.workforce.runtime.RuntimeExecutionContext;
import com.metatron.workforce.runtime.RuntimeInstance;
import com.metatron.workforce.runtime.execution.RuntimeExecutionCoordinator;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Thin composition layer connecting Workforce management state to Workplace queue,
 * authorization, Execution, and runtime binding. It owns none of those downstream semantics.
 */
public final class AutonomousManagementCoordinator {
    private final ManagementAutonomyService management;
    private final WorkQueueService workQueue;
    private final AuthorizationService authorization;
    private final ExecutionService execution;
    private final RuntimeExecutionCoordinator runtimeExecution;

    public AutonomousManagementCoordinator(ManagementAutonomyService management, WorkQueueService workQueue,
            AuthorizationService authorization, ExecutionService execution,
            RuntimeExecutionCoordinator runtimeExecution) {
        this.management = Objects.requireNonNull(management, "management");
        this.workQueue = Objects.requireNonNull(workQueue, "workQueue");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.runtimeExecution = Objects.requireNonNull(runtimeExecution, "runtimeExecution");
    }

    public ObjectiveIntake acceptObjectiveFromHuman(String objectiveId, String directorWorkerId,
            String organizationContextId, String description, ActorRef human, Instant at) {
        ManagementObjective objective = management.acceptObjective(
                objectiveId, directorWorkerId, organizationContextId, description, at);
        ActorRef director = new ActorRef(directorWorkerId, ActorRef.ActorType.WORKER);
        WorkQueueItem queue = workQueue.create(director, organizationContextId, human,
                WorkQueueItem.ItemType.REQUEST, objectiveId, WorkQueueItem.Priority.HIGH, null);
        queue = workQueue.deliver(queue.queueItemId());
        queue = workQueue.acknowledge(queue.queueItemId());
        return new ObjectiveIntake(objective, queue);
    }

    /**
     * Detects a capability/capacity gap and exposes it as a legitimate Workplace staffing proposal.
     * Participant recognition/admission remains outside this service.
     */
    public Optional<StaffingAction> assessAndRaiseStaffingNeed(String objectiveId, String directorWorkerId,
            String organizationContextId, String requiredCapability, double requiredCapacity,
            double availableCapacity, ActorRef staffingAuthority, Instant at) {
        Optional<StaffingNeed> need = management.assessCapacity(objectiveId, directorWorkerId,
                requiredCapability, requiredCapacity, availableCapacity, at);
        if (need.isEmpty()) return Optional.empty();
        String staffingRef = "staffing:" + objectiveId + ":" + UUID.randomUUID();
        WorkQueueItem queue = workQueue.create(staffingAuthority, organizationContextId,
                new ActorRef(directorWorkerId, ActorRef.ActorType.WORKER),
                WorkQueueItem.ItemType.PROPOSAL, staffingRef, WorkQueueItem.Priority.HIGH, null);
        queue = workQueue.deliver(queue.queueItemId());
        return Optional.of(new StaffingAction(need.get(), staffingRef, queue));
    }

    public ManagedExecution authorizeBindAndExecute(String objectiveId, String directorWorkerId,
            String assignmentId, String workPackageId, WorkProposal proposal, ApprovalDecision approval,
            AuthorizationRequest request, RuntimeInstance runtime, ExecutionService.Executor executor,
            String executionId, Instant startedAt, Instant completedAt) {
        management.addAssignmentReference(objectiveId, directorWorkerId, assignmentId, startedAt);

        AuthorizationService.AuthorizationResult decision = authorization.authorize(proposal, approval, request);
        if (!decision.allowed()) throw new SecurityException(decision.reason());

        ExecutionHandoffRequest handoff = new ExecutionHandoffRequest(
                "handoff:" + executionId, directorWorkerId, assignmentId,
                decision.authorizationReference(), workPackageId, executionId, startedAt);
        RuntimeExecutionContext runtimeContext = runtimeExecution.start(
                runtime, executionId, assignmentId, decision.authorizationReference());

        ExecutionRecord record = execution.executeAuthorized(proposal, approval, request, authorization,
                executor, executionId, startedAt, completedAt, null);
        return new ManagedExecution(handoff, runtimeContext, record);
    }

    public DeliveryResult deliverWithEvidence(String objectiveId, String directorWorkerId,
            String organizationContextId, List<String> evidenceRefs, ActorRef humanRecipient, Instant at) {
        ManagementObjective delivered = management.deliver(objectiveId, directorWorkerId, evidenceRefs, at);
        WorkQueueItem report = workQueue.create(humanRecipient, organizationContextId,
                new ActorRef(directorWorkerId, ActorRef.ActorType.WORKER), WorkQueueItem.ItemType.REPORT,
                objectiveId, WorkQueueItem.Priority.HIGH, null);
        report = workQueue.deliver(report.queueItemId());
        return new DeliveryResult(delivered, report);
    }

    public record ObjectiveIntake(ManagementObjective objective, WorkQueueItem queueItem) {}
    public record StaffingAction(StaffingNeed need, String staffingReference, WorkQueueItem queueItem) {}
    public record ManagedExecution(ExecutionHandoffRequest handoff, RuntimeExecutionContext runtimeContext,
                                   ExecutionRecord executionRecord) {}
    public record DeliveryResult(ManagementObjective objective, WorkQueueItem reportQueueItem) {}
}
