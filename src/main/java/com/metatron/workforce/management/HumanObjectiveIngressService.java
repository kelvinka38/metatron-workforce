package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionObjectiveHandoff;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.phase3.ActorRef;
import com.metatron.workforce.phase3.AuthorizationContext;
import com.metatron.workforce.phase3.AuthorizationPolicy;
import com.metatron.workforce.phase3.WorkQueueItem;
import com.metatron.workforce.phase3.WorkQueueService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * General Human execution-intent ingress into Workforce management.
 *
 * The ingress is intentionally bounded: it authorizes only the workplace act of submitting an
 * authenticated Human request to the configured Head Worker. It does not authorize execution.
 * Downstream Assignment, Authorization, Gateway and Execution admission remain mandatory.
 */
@Service
public final class HumanObjectiveIngressService implements ExecutionObjectiveHandoff {
    private final ManagementAutonomyService management;
    private final String headWorkerId;
    private final WorkQueueService workQueue;

    public HumanObjectiveIngressService(
            ManagementAutonomyService management,
            @Value("${workforce.management.head-worker-id:${METATRON_HEAD_WORKER_ID:metatron-workforce}}") String headWorkerId) {
        this(management, headWorkerId, Clock.systemUTC());
    }

    HumanObjectiveIngressService(ManagementAutonomyService management, String headWorkerId, Clock clock) {
        this.management = Objects.requireNonNull(management, "management");
        this.headWorkerId = requireText(headWorkerId, "headWorkerId");
        Objects.requireNonNull(clock, "clock");
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

        String objectiveId = "objective:intelligence-case:" + admittedCaseId;
        Instant now = Instant.now();
        ManagementObjective objective;
        try {
            objective = management.get(objectiveId);
        } catch (IllegalArgumentException unknown) {
            // These references authorize only request intake/coordination. They are deliberately
            // not execution Authorization references and cannot be used by an execution boundary.
            objective = management.acceptObjective(
                    objectiveId,
                    headWorkerId,
                    admittedOrganizationContextId,
                    renderObjective(request, admittedChannel, admittedExternalMessageReference),
                    "human:" + admittedHumanId,
                    "authority:workplace-request-intake",
                    "authorization:workplace-request-only:" + admittedExternalMessageReference,
                    now);
        }

        ActorRef human = new ActorRef(admittedHumanId, ActorRef.ActorType.HUMAN);
        ActorRef head = new ActorRef(headWorkerId, ActorRef.ActorType.WORKER);
        WorkQueueItem queue = workQueue.items().stream()
                .filter(item -> item.referencedObjectId().equals(objectiveId)
                        && item.sourceActor().equals(human)
                        && item.recipient().equals(head))
                .findFirst()
                .orElseGet(() -> {
                    WorkQueueItem created = workQueue.create(
                            head, admittedOrganizationContextId, human,
                            WorkQueueItem.ItemType.REQUEST, objectiveId, WorkQueueItem.Priority.HIGH, null);
                    WorkQueueItem delivered = workQueue.deliver(created.queueItemId());
                    return workQueue.acknowledge(delivered.queueItemId());
                });

        return new HandoffReceipt(
                true,
                objective.objectiveId(),
                objective.ownerWorkerId(),
                queue.queueItemId(),
                objective.status().name(),
                "AWAITING_ASSIGNMENT_AUTHORIZATION_AND_EXECUTION_ADMISSION",
                "REQUEST_ACCEPTED_BY_WORKFORCE");
    }

    private static String renderObjective(NormalizedRequest request, String channel, String externalMessageReference) {
        StringBuilder description = new StringBuilder(request.objective());
        if (!request.target().isBlank()) description.append("\nTarget: ").append(request.target());
        if (!request.constraints().isEmpty()) description.append("\nConstraints: ").append(String.join("; ", request.constraints()));
        if (!request.explicitProhibitions().isEmpty()) description.append("\nProhibitions: ").append(String.join("; ", request.explicitProhibitions()));
        description.append("\nIngress: ").append(channel).append("/").append(externalMessageReference);
        return description.toString();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
