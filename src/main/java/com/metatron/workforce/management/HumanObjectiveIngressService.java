package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionObjectiveHandoff;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.phase3.ActorRef;
import com.metatron.workforce.phase3.AuthorizationContext;
import com.metatron.workforce.phase3.AuthorizationPolicy;
import com.metatron.workforce.phase3.WorkQueueItem;
import com.metatron.workforce.phase3.WorkQueueService;
import com.metatron.workforce.workplace.MeetingWorkerDirectory;
import com.metatron.workforce.workplace.WorkplaceContinuityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * General Human execution-intent ingress into Workforce autonomous management.
 *
 * The ingress atomically accepts and persists an Objective owned by the canonical admitted Worker,
 * acknowledges it, and detaches. Generic Human Work remains Head-owned; selected-Worker instructions
 * are revalidated against the canonical active Worker directory before targeted ownership is accepted.
 * Planning, staffing checks and execution are owned by the persistent management runner. The accepted
 * Objective is also bound to the existing canonical Workplace Conversation reference; Workforce does
 * not create or redefine Conversation/Meeting/Decision objects here.
 */
@Service
public final class HumanObjectiveIngressService implements ExecutionObjectiveHandoff {
    private final ManagementAutonomyService management;
    private final String headWorkerId;
    private final WorkQueueService workQueue;
    private final List<String> capabilityCatalog;
    private final AutonomousManagementRunner runner;
    private final WorkplaceContinuityService workplaceContinuity;
    private final Function<String, String> targetWorkerResolver;
    private final Clock clock;

    @Autowired
    public HumanObjectiveIngressService(
            ManagementAutonomyService management,
            List<AutonomousExecutionCapability> executionCapabilities,
            AutonomousManagementRunner runner,
            WorkplaceContinuityService workplaceContinuity,
            MeetingWorkerDirectory workerDirectory,
            @Value("${workforce.management.head-worker-id:${METATRON_HEAD_WORKER_ID:metatron-workforce}}") String headWorkerId) {
        this(management, executionCapabilities, runner, workplaceContinuity,
                workerId -> workerDirectory.resolveActiveById(workerId).workerId(),
                headWorkerId, Clock.systemUTC());
    }

    HumanObjectiveIngressService(ManagementAutonomyService management, String headWorkerId, Clock clock) {
        this(management, List.of(), headWorkerId, clock);
    }

    HumanObjectiveIngressService(ManagementAutonomyService management,
                                 List<AutonomousExecutionCapability> executionCapabilities,
                                 String headWorkerId, Clock clock) {
        this(management, executionCapabilities,
                new AutonomousManagementRunner(management,
                        (caseId, request, available) -> request.executionWorkPlan(),
                        executionCapabilities, clock),
                null, Function.identity(), headWorkerId, clock);
    }

    HumanObjectiveIngressService(ManagementAutonomyService management,
                                 List<AutonomousExecutionCapability> executionCapabilities,
                                 AutonomousManagementRunner runner,
                                 String headWorkerId, Clock clock) {
        this(management, executionCapabilities, runner, null, Function.identity(), headWorkerId, clock);
    }

    HumanObjectiveIngressService(ManagementAutonomyService management,
                                 List<AutonomousExecutionCapability> executionCapabilities,
                                 AutonomousManagementRunner runner,
                                 WorkplaceContinuityService workplaceContinuity,
                                 String headWorkerId, Clock clock) {
        this(management, executionCapabilities, runner, workplaceContinuity,
                Function.identity(), headWorkerId, clock);
    }

    HumanObjectiveIngressService(ManagementAutonomyService management,
                                 List<AutonomousExecutionCapability> executionCapabilities,
                                 AutonomousManagementRunner runner,
                                 WorkplaceContinuityService workplaceContinuity,
                                 Function<String, String> targetWorkerResolver,
                                 String headWorkerId, Clock clock) {
        this.management = Objects.requireNonNull(management, "management");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.workplaceContinuity = workplaceContinuity;
        this.targetWorkerResolver = Objects.requireNonNull(targetWorkerResolver, "targetWorkerResolver");
        this.headWorkerId = requireText(headWorkerId, "headWorkerId");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(executionCapabilities, "executionCapabilities");
        Map<String, AutonomousExecutionCapability> registered = new LinkedHashMap<>();
        for (AutonomousExecutionCapability capability : executionCapabilities) {
            String ref = requireText(capability.capabilityRef(), "capabilityRef");
            if (registered.putIfAbsent(ref, capability) != null) {
                throw new IllegalStateException("duplicate autonomous execution capability: " + ref);
            }
        }
        this.capabilityCatalog = registered.keySet().stream().sorted().toList();
        AuthorizationPolicy requestAdmission = (source, recipient, organizationContextId) -> {
            boolean allowed = source.type() == ActorRef.ActorType.HUMAN
                    && recipient.type() == ActorRef.ActorType.WORKER
                    && recipient.actorId() != null && !recipient.actorId().isBlank()
                    && organizationContextId != null && !organizationContextId.isBlank();
            String reference = "workplace-request-admission:" + source.actorId() + ":" + recipient.actorId();
            return allowed ? AuthorizationContext.allowed(reference) : AuthorizationContext.denied(reference);
        };
        this.workQueue = new WorkQueueService(requestAdmission, clock);
    }

    @Override
    public List<String> capabilityCatalog() {
        return capabilityCatalog;
    }

    @Override
    public synchronized HandoffReceipt submit(
            String humanId,
            String organizationContextId,
            String caseId,
            String conversationId,
            String externalMessageReference,
            String channel,
            NormalizedRequest request) {
        return submitOwned(headWorkerId, humanId, organizationContextId, caseId, conversationId,
                externalMessageReference, channel, request);
    }

    @Override
    public synchronized HandoffReceipt submitToWorker(
            String ownerWorkerId,
            String humanId,
            String organizationContextId,
            String caseId,
            String conversationId,
            String externalMessageReference,
            String channel,
            NormalizedRequest request) {
        String canonicalOwnerWorkerId = requireText(
                targetWorkerResolver.apply(requireText(ownerWorkerId, "ownerWorkerId")),
                "canonicalOwnerWorkerId");
        return submitOwned(canonicalOwnerWorkerId, humanId, organizationContextId,
                caseId, conversationId, externalMessageReference, channel, request);
    }

    private HandoffReceipt submitOwned(
            String ownerWorkerId,
            String humanId,
            String organizationContextId,
            String caseId,
            String conversationId,
            String externalMessageReference,
            String channel,
            NormalizedRequest request) {
        final String admittedOwnerWorkerId = requireText(ownerWorkerId, "ownerWorkerId");
        final String admittedHumanId = requireText(humanId, "humanId");
        final String admittedOrganizationContextId = requireText(organizationContextId, "organizationContextId");
        final String admittedCaseId = requireText(caseId, "caseId");
        final String admittedConversationId = requireText(conversationId, "conversationId");
        final String admittedExternalMessageReference = requireText(externalMessageReference, "externalMessageReference");
        final String admittedChannel = requireText(channel, "channel");
        Objects.requireNonNull(request, "request");

        String objectiveId = objectiveId(admittedCaseId, admittedExternalMessageReference);
        String requestAdmissionReference = "workplace-request-admission:" + admittedHumanId + ":" + admittedOwnerWorkerId;
        Instant now = clock.instant();
        ManagementObjective objective;
        try {
            objective = management.get(objectiveId);
            if (!objective.ownerWorkerId().equals(admittedOwnerWorkerId)) {
                throw new SecurityException("objective owner mismatch on replay: " + objectiveId);
            }
        } catch (IllegalArgumentException unknown) {
            objective = management.acceptHumanObjective(
                    objectiveId,
                    admittedOwnerWorkerId,
                    admittedOrganizationContextId,
                    renderObjective(request, admittedCaseId, admittedConversationId,
                            admittedChannel, admittedExternalMessageReference),
                    "human:" + admittedHumanId,
                    requestAdmissionReference,
                    admittedCaseId,
                    admittedConversationId,
                    admittedExternalMessageReference,
                    admittedChannel,
                    request,
                    now);
        }

        if (workplaceContinuity != null) {
            workplaceContinuity.bindAcceptedObjective(
                    objective.objectiveId(), admittedHumanId, admittedConversationId,
                    admittedChannel, admittedExternalMessageReference, requestAdmissionReference, now);
        }

        WorkQueueItem queue = ensureQueue(objectiveId, admittedOwnerWorkerId,
                admittedHumanId, admittedOrganizationContextId);

        if (objective.status() == ManagementObjective.Status.COMPLETED
                || objective.status() == ManagementObjective.Status.DELIVERED) {
            return new HandoffReceipt(true, objective.objectiveId(), objective.ownerWorkerId(), queue.queueItemId(),
                    objective.status().name(), "COMPLETED", "WORK_ALREADY_COMPLETED_BY_WORKFORCE");
        }

        if (objective.status() == ManagementObjective.Status.BLOCKED
                || objective.status() == ManagementObjective.Status.ESCALATED) {
            return new HandoffReceipt(true, objective.objectiveId(), objective.ownerWorkerId(), queue.queueItemId(),
                    objective.status().name(), "BLOCKED", "OBJECTIVE_REQUIRES_RECOVERY_OR_ESCALATION");
        }

        runner.wake();
        return new HandoffReceipt(true, objective.objectiveId(), objective.ownerWorkerId(), queue.queueItemId(),
                objective.status().name(), "ACCEPTED", "OBJECTIVE_ACCEPTED_FOR_AUTONOMOUS_MANAGEMENT");
    }

    private WorkQueueItem ensureQueue(String objectiveId, String ownerWorkerId,
                                      String humanId, String organizationContextId) {
        ActorRef human = new ActorRef(humanId, ActorRef.ActorType.HUMAN);
        ActorRef owner = new ActorRef(ownerWorkerId, ActorRef.ActorType.WORKER);
        return workQueue.items().stream()
                .filter(item -> item.referencedObjectId().equals(objectiveId)
                        && item.sourceActor().equals(human)
                        && item.recipient().equals(owner))
                .findFirst()
                .orElseGet(() -> {
                    WorkQueueItem created = workQueue.create(
                            owner, organizationContextId, human,
                            WorkQueueItem.ItemType.REQUEST, objectiveId, WorkQueueItem.Priority.HIGH, null);
                    WorkQueueItem delivered = workQueue.deliver(created.queueItemId());
                    return workQueue.acknowledge(delivered.queueItemId());
                });
    }

    private static String objectiveId(String caseId, String externalMessageReference) {
        return "objective:intelligence-case:" + caseId + ":request:" + externalMessageReference;
    }

    private static String renderObjective(NormalizedRequest request, String caseId, String conversationId,
                                          String channel, String externalMessageReference) {
        StringBuilder description = new StringBuilder(request.objective());
        if (!request.target().isBlank()) description.append("\nTarget: ").append(request.target());
        if (!request.constraints().isEmpty()) description.append("\nConstraints: ").append(String.join("; ", request.constraints()));
        if (!request.explicitProhibitions().isEmpty()) description.append("\nProhibitions: ").append(String.join("; ", request.explicitProhibitions()));
        if (!request.executionWorkPlan().isEmpty()) description.append("\nPlanned steps: ").append(request.executionWorkPlan().size());
        description.append("\nCase: ").append(caseId)
                .append("\nConversation: ").append(conversationId)
                .append("\nIngress: ").append(channel).append("/").append(externalMessageReference);
        return description.toString();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
