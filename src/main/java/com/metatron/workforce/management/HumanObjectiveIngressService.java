package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionObjectiveHandoff;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.phase3.ActorRef;
import com.metatron.workforce.phase3.AuthorizationContext;
import com.metatron.workforce.phase3.AuthorizationPolicy;
import com.metatron.workforce.phase3.WorkQueueItem;
import com.metatron.workforce.phase3.WorkQueueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * General Human execution-intent ingress into Workforce autonomous management.
 *
 * The ingress admits a Human request as a Head-owned Objective, executes only capability-backed
 * work that has its own legitimate admission/authorization boundary, and fails closed into
 * staffing/blocker state when the required institutional capability is unavailable.
 */
@Service
public final class HumanObjectiveIngressService implements ExecutionObjectiveHandoff {
    private final ManagementAutonomyService management;
    private final String headWorkerId;
    private final WorkQueueService workQueue;
    private final Map<String, AutonomousExecutionCapability> capabilities;

    @Autowired
    public HumanObjectiveIngressService(
            ManagementAutonomyService management,
            List<AutonomousExecutionCapability> executionCapabilities,
            @Value("${workforce.management.head-worker-id:${METATRON_HEAD_WORKER_ID:metatron-workforce}}") String headWorkerId) {
        this(management, executionCapabilities, headWorkerId, Clock.systemUTC());
    }

    HumanObjectiveIngressService(ManagementAutonomyService management, String headWorkerId, Clock clock) {
        this(management, List.of(), headWorkerId, clock);
    }

    HumanObjectiveIngressService(ManagementAutonomyService management,
                                 List<AutonomousExecutionCapability> executionCapabilities,
                                 String headWorkerId, Clock clock) {
        this.management = Objects.requireNonNull(management, "management");
        this.headWorkerId = requireText(headWorkerId, "headWorkerId");
        Objects.requireNonNull(executionCapabilities, "executionCapabilities");
        Objects.requireNonNull(clock, "clock");
        Map<String, AutonomousExecutionCapability> registered = new LinkedHashMap<>();
        for (AutonomousExecutionCapability capability : executionCapabilities) {
            String ref = requireText(capability.capabilityRef(), "capabilityRef");
            if (registered.putIfAbsent(ref, capability) != null) {
                throw new IllegalStateException("duplicate autonomous execution capability: " + ref);
            }
        }
        this.capabilities = Map.copyOf(registered);
        AuthorizationPolicy requestAdmission = (source, recipient, organizationContextId) -> {
            boolean allowed = source.type() == ActorRef.ActorType.HUMAN
                    && recipient.type() == ActorRef.ActorType.WORKER
                    && this.headWorkerId.equals(recipient.actorId())
                    && organizationContextId != null && !organizationContextId.isBlank();
            String reference = "workplace-request-admission:" + source.actorId() + ":" + recipient.actorId();
            return allowed ? AuthorizationContext.allowed(reference) : AuthorizationContext.denied(reference);
        };
        this.workQueue = new WorkQueueService(requestAdmission, clock);
    }

    @Override
    public List<String> capabilityCatalog() {
        return capabilities.keySet().stream().sorted().toList();
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
        final String admittedHumanId = requireText(humanId, "humanId");
        final String admittedOrganizationContextId = requireText(organizationContextId, "organizationContextId");
        final String admittedCaseId = requireText(caseId, "caseId");
        requireText(conversationId, "conversationId");
        final String admittedExternalMessageReference = requireText(externalMessageReference, "externalMessageReference");
        final String admittedChannel = requireText(channel, "channel");
        Objects.requireNonNull(request, "request");

        /*
         * A Case coordinates one bounded intelligence problem and may reference many institutional Objectives.
         * Therefore Objective identity is request-scoped, not Case-scoped. The provider-neutral external message
         * reference supplies stable request-instance correlation/idempotency only; it is never Authority evidence.
         * Re-delivery of the same provider request remains idempotent, while a new Human execution request in the
         * same Case receives its own Objective/queue/execution lifecycle.
         */
        String objectiveId = objectiveId(admittedCaseId, admittedExternalMessageReference);
        Instant now = Instant.now();
        ManagementObjective objective;
        try {
            objective = management.get(objectiveId);
        } catch (IllegalArgumentException unknown) {
            objective = management.acceptHumanObjective(
                    objectiveId,
                    headWorkerId,
                    admittedOrganizationContextId,
                    renderObjective(request, admittedCaseId, conversationId, admittedChannel, admittedExternalMessageReference),
                    "human:" + admittedHumanId,
                    "workplace-request-admission:" + admittedHumanId + ":" + headWorkerId,
                    now);
        }

        WorkQueueItem queue = ensureQueue(objectiveId, admittedHumanId, admittedOrganizationContextId);

        if (objective.status() == ManagementObjective.Status.DELIVERED) {
            return new HandoffReceipt(true, objective.objectiveId(), objective.ownerWorkerId(), queue.queueItemId(),
                    objective.status().name(), "COMPLETED", "WORK_ALREADY_COMPLETED_BY_WORKFORCE");
        }

        if (objective.status() == ManagementObjective.Status.BLOCKED
                || objective.status() == ManagementObjective.Status.ESCALATED) {
            objective = management.recoverLocally(objectiveId, headWorkerId,
                    "re-evaluate semantic work plan against current capability inventory", Instant.now());
        }

        if (request.executionWorkPlan().isEmpty()) {
            ManagementObjective blocked = management.markBlocked(objectiveId, headWorkerId,
                    "execution-plan-missing", Instant.now());
            return new HandoffReceipt(true, blocked.objectiveId(), blocked.ownerWorkerId(), queue.queueItemId(),
                    blocked.status().name(), "PLANNING_REQUIRED", "SEMANTIC_EXECUTION_PLAN_MISSING");
        }

        Map<String, Boolean> completed = new LinkedHashMap<>();
        List<String> evidence = new ArrayList<>();
        for (ExecutionWorkSpec step : request.executionWorkPlan()) {
            boolean dependenciesSatisfied = step.dependsOn().stream().allMatch(dep -> Boolean.TRUE.equals(completed.get(dep)));
            if (!dependenciesSatisfied) {
                ManagementObjective blocked = management.markBlocked(objectiveId, headWorkerId,
                        "dependency-not-completed:" + step.stepId(), Instant.now());
                return new HandoffReceipt(true, blocked.objectiveId(), blocked.ownerWorkerId(), queue.queueItemId(),
                        blocked.status().name(), "DEPENDENCY_BLOCKED:" + step.stepId(), "WORK_DEPENDENCY_NOT_COMPLETED");
            }

            AutonomousExecutionCapability capability = capabilities.get(step.requiredCapability());
            if (capability == null) {
                management.assessCapacity(objectiveId, headWorkerId, step.requiredCapability(), 1.0, 0.0, Instant.now());
                ManagementObjective blocked = management.markBlocked(objectiveId, headWorkerId,
                        "staffing-required:" + step.requiredCapability(), Instant.now());
                return new HandoffReceipt(true, blocked.objectiveId(), blocked.ownerWorkerId(), queue.queueItemId(),
                        blocked.status().name(), "STAFFING_REQUIRED:" + step.requiredCapability(),
                        "REQUIRED_CAPABILITY_UNAVAILABLE");
            }

            management.assessCapacity(objectiveId, headWorkerId, step.requiredCapability(), 1.0, 1.0, Instant.now());
            AutonomousExecutionCapability.CapabilityResult result;
            try {
                result = capability.execute(new AutonomousExecutionCapability.CapabilityRequest(
                        admittedHumanId, admittedOrganizationContextId, objectiveId, step));
            } catch (RuntimeException failure) {
                ManagementObjective blocked = management.markBlocked(objectiveId, headWorkerId,
                        "capability-execution-failed:" + step.requiredCapability() + ":" + failure.getMessage(), Instant.now());
                return new HandoffReceipt(true, blocked.objectiveId(), blocked.ownerWorkerId(), queue.queueItemId(),
                        blocked.status().name(), "EXECUTION_BLOCKED:" + step.requiredCapability(),
                        "CAPABILITY_EXECUTION_FAILED");
            }

            if (!result.assignmentReference().isBlank()) {
                management.addAssignmentReference(objectiveId, headWorkerId, result.assignmentReference(), Instant.now());
            }
            evidence.addAll(result.evidenceReferences());
            evidence.add("autonomous-step:" + step.stepId() + ":capability=" + step.requiredCapability()
                    + ":work=" + result.workReference() + ":worker=" + result.workerId());
            if (!result.success()) {
                ManagementObjective blocked = management.markBlocked(objectiveId, headWorkerId,
                        "worker-result-failed:" + step.stepId() + ":" + result.summary(), Instant.now());
                return new HandoffReceipt(true, blocked.objectiveId(), blocked.ownerWorkerId(), queue.queueItemId(),
                        blocked.status().name(), "EXECUTION_FAILED:" + step.stepId(), "WORKER_EXECUTION_FAILED");
            }
            completed.put(step.stepId(), true);
        }

        if (evidence.isEmpty()) {
            ManagementObjective blocked = management.markBlocked(objectiveId, headWorkerId,
                    "execution-produced-no-evidence", Instant.now());
            return new HandoffReceipt(true, blocked.objectiveId(), blocked.ownerWorkerId(), queue.queueItemId(),
                    blocked.status().name(), "EVIDENCE_REQUIRED", "NO_EXECUTION_EVIDENCE");
        }

        ManagementObjective delivered = management.deliver(objectiveId, headWorkerId, evidence, Instant.now());
        return new HandoffReceipt(true, delivered.objectiveId(), delivered.ownerWorkerId(), queue.queueItemId(),
                delivered.status().name(), "COMPLETED", "WORK_COMPLETED_BY_WORKFORCE");
    }

    private WorkQueueItem ensureQueue(String objectiveId, String humanId, String organizationContextId) {
        ActorRef human = new ActorRef(humanId, ActorRef.ActorType.HUMAN);
        ActorRef head = new ActorRef(headWorkerId, ActorRef.ActorType.WORKER);
        return workQueue.items().stream()
                .filter(item -> item.referencedObjectId().equals(objectiveId)
                        && item.sourceActor().equals(human)
                        && item.recipient().equals(head))
                .findFirst()
                .orElseGet(() -> {
                    WorkQueueItem created = workQueue.create(
                            head, organizationContextId, human,
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
