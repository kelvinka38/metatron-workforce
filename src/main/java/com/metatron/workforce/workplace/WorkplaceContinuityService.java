package com.metatron.workforce.workplace;

import com.metatron.workforce.management.AutonomousObjectiveWork;
import com.metatron.workforce.management.ManagementAutonomyService;
import com.metatron.workforce.management.ManagementObjective;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Durable integration with canonical Workplace identities.
 *
 * Workforce persists only references to Conversation/Meeting/Decision and channel authorization.
 * It never creates or redefines those Workplace objects. Query and delivery across channels require
 * an external authorization verifier; missing verification fails closed.
 */
public final class WorkplaceContinuityService {
    private final Map<String, WorkplaceContinuityRecord> continuity = new LinkedHashMap<>();
    private final Map<String, WorkplaceDelivery> deliveries = new LinkedHashMap<>();
    private final WorkplaceContinuityStateStore store;
    private final ManagementAutonomyService management;
    private final List<WorkplaceChannelAuthorizationVerifier> authorizationVerifiers;
    private final List<WorkplaceDeliveryAdapter> deliveryAdapters;

    public WorkplaceContinuityService(WorkplaceContinuityStateStore store,
                                      ManagementAutonomyService management,
                                      List<WorkplaceChannelAuthorizationVerifier> authorizationVerifiers,
                                      List<WorkplaceDeliveryAdapter> deliveryAdapters) {
        this.store = Objects.requireNonNull(store, "store");
        this.management = Objects.requireNonNull(management, "management");
        this.authorizationVerifiers = List.copyOf(authorizationVerifiers == null ? List.of() : authorizationVerifiers);
        this.deliveryAdapters = List.copyOf(deliveryAdapters == null ? List.of() : deliveryAdapters);
        WorkplaceContinuityStateStore.Snapshot snapshot = store.load();
        continuity.putAll(snapshot.continuity());
        deliveries.putAll(snapshot.deliveries());
    }

    public synchronized WorkplaceContinuityRecord bindAcceptedObjective(
            String objectiveId, String humanId, String conversationRef,
            String ingressChannel, String ingressMessageRef, String requestAdmissionRef, Instant at) {
        require(objectiveId, "objectiveId");
        require(humanId, "humanId");
        require(conversationRef, "conversationRef");
        require(ingressChannel, "ingressChannel");
        require(ingressMessageRef, "ingressMessageRef");
        require(requestAdmissionRef, "requestAdmissionRef");
        Objects.requireNonNull(at, "at");
        WorkplaceContinuityRecord existing = continuity.get(objectiveId);
        if (existing != null) {
            if (!existing.humanId().equals(humanId)
                    || !existing.conversationRef().equals(conversationRef)
                    || !existing.ingressMessageRef().equals(ingressMessageRef)) {
                throw new IllegalStateException("Workplace continuity identity conflict: " + objectiveId);
            }
            return existing;
        }
        WorkplaceContinuityRecord created = new WorkplaceContinuityRecord(
                objectiveId, humanId, conversationRef, ingressChannel, ingressMessageRef,
                requestAdmissionRef, Map.of(), List.of(), List.of(), List.of(), at, at);
        continuity.put(objectiveId, created);
        persist();
        return created;
    }

    public synchronized WorkplaceContinuityRecord authorizeChannel(
            String objectiveId, String humanId, String channel, String authorizationRef,
            WorkplaceChannelAuthorizationVerifier.Purpose purpose, Instant at) {
        WorkplaceContinuityRecord current = requireContinuity(objectiveId);
        requireHuman(current, humanId);
        require(channel, "channel");
        require(authorizationRef, "authorizationRef");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(at, "at");
        boolean verified = authorizationVerifiers.stream().anyMatch(verifier ->
                verifier.verifies(objectiveId, humanId, channel, authorizationRef, purpose));
        if (!verified) throw new SecurityException("Workplace channel authorization not verified");
        Map<String, String> authorizations = new LinkedHashMap<>(current.channelAuthorizationRefs());
        authorizations.put(authKey(purpose, channel), authorizationRef);
        WorkplaceContinuityRecord updated = copy(current, authorizations,
                current.meetingRefs(), current.decisionRefs(), current.evidenceRefs(), at);
        continuity.put(objectiveId, updated);
        persist();
        return updated;
    }

    public synchronized WorkplaceContinuityRecord referenceMeeting(String objectiveId, String meetingRef, Instant at) {
        return addReference(objectiveId, meetingRef, ReferenceType.MEETING, at);
    }

    public synchronized WorkplaceContinuityRecord referenceDecision(String objectiveId, String decisionRef, Instant at) {
        return addReference(objectiveId, decisionRef, ReferenceType.DECISION, at);
    }

    public synchronized WorkplaceContinuityRecord referenceEvidence(String objectiveId, String evidenceRef, Instant at) {
        return addReference(objectiveId, evidenceRef, ReferenceType.EVIDENCE, at);
    }

    public synchronized ProgressProjection progress(
            String objectiveId, String humanId, String queryChannel, String authorizationRef) {
        WorkplaceContinuityRecord current = requireContinuity(objectiveId);
        requireHuman(current, humanId);
        requireChannelAuthorization(current, queryChannel, authorizationRef,
                WorkplaceChannelAuthorizationVerifier.Purpose.QUERY);
        ManagementObjective objective = management.get(objectiveId);
        AutonomousObjectiveWork work = management.findAutonomousWork(objectiveId).orElse(null);
        return new ProgressProjection(
                objective.objectiveId(), objective.ownerWorkerId(), objective.status().name(),
                current.conversationRef(), current.meetingRefs(), current.decisionRefs(),
                objective.assignmentRefs(), mergeEvidence(current.evidenceRefs(), objective.evidenceRefs()),
                work == null ? 0 : work.plannedWork().size(),
                work == null ? 0 : work.completedStepIds().size(),
                work == null ? "" : work.blocker(),
                work == null ? "UNKNOWN" : work.status().name(),
                current.ingressChannel(), queryChannel);
    }

    public synchronized WorkplaceDelivery queueDelivery(
            String objectiveId, String humanId, String channel, String authorizationRef,
            String payloadReference, Instant at) {
        WorkplaceContinuityRecord current = requireContinuity(objectiveId);
        requireHuman(current, humanId);
        requireChannelAuthorization(current, channel, authorizationRef,
                WorkplaceChannelAuthorizationVerifier.Purpose.DELIVERY);
        require(payloadReference, "payloadReference");
        Objects.requireNonNull(at, "at");
        String deliveryId = objectiveId + ":delivery:" + channel + ":" + Integer.toUnsignedString(
                Objects.hash(payloadReference, authorizationRef), 16);
        WorkplaceDelivery existing = deliveries.get(deliveryId);
        if (existing != null) return existing;
        WorkplaceDelivery delivery = new WorkplaceDelivery(
                deliveryId, objectiveId, current.conversationRef(), humanId, channel,
                authorizationRef, payloadReference, WorkplaceDelivery.Status.PENDING, "", "", at, at);
        deliveries.put(deliveryId, delivery);
        persist();
        return delivery;
    }

    public synchronized WorkplaceDelivery deliver(String deliveryId, Instant at) {
        WorkplaceDelivery current = requireDelivery(deliveryId);
        Objects.requireNonNull(at, "at");
        if (current.status() == WorkplaceDelivery.Status.DELIVERED) return current;
        WorkplaceDeliveryAdapter adapter = deliveryAdapters.stream()
                .filter(candidate -> candidate.supports(current.channel())).findFirst()
                .orElseThrow(() -> new IllegalStateException("delivery-adapter-unavailable:" + current.channel()));
        try {
            String externalRef = adapter.deliver(current);
            require(externalRef, "externalDeliveryRef");
            WorkplaceDelivery delivered = copyDelivery(current, WorkplaceDelivery.Status.DELIVERED,
                    externalRef, "", at);
            deliveries.put(deliveryId, delivered);
            persist();
            return delivered;
        } catch (RuntimeException failure) {
            WorkplaceDelivery failed = copyDelivery(current, WorkplaceDelivery.Status.FAILED,
                    "", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(), at);
            deliveries.put(deliveryId, failed);
            persist();
            throw failure;
        }
    }

    public synchronized WorkplaceContinuityRecord continuity(String objectiveId) {
        return requireContinuity(objectiveId);
    }

    public synchronized List<WorkplaceDelivery> deliveries(String objectiveId) {
        return deliveries.values().stream().filter(delivery -> delivery.objectiveId().equals(objectiveId))
                .sorted(Comparator.comparing(WorkplaceDelivery::createdAt)).toList();
    }

    private WorkplaceContinuityRecord addReference(
            String objectiveId, String reference, ReferenceType type, Instant at) {
        WorkplaceContinuityRecord current = requireContinuity(objectiveId);
        require(reference, "reference");
        Objects.requireNonNull(at, "at");
        List<String> meetings = new ArrayList<>(current.meetingRefs());
        List<String> decisions = new ArrayList<>(current.decisionRefs());
        List<String> evidence = new ArrayList<>(current.evidenceRefs());
        switch (type) {
            case MEETING -> addDistinct(meetings, reference);
            case DECISION -> addDistinct(decisions, reference);
            case EVIDENCE -> addDistinct(evidence, reference);
        }
        WorkplaceContinuityRecord updated = copy(current, current.channelAuthorizationRefs(),
                meetings, decisions, evidence, at);
        continuity.put(objectiveId, updated);
        persist();
        return updated;
    }

    private static WorkplaceContinuityRecord copy(
            WorkplaceContinuityRecord current, Map<String, String> authorizations,
            List<String> meetings, List<String> decisions, List<String> evidence, Instant at) {
        return new WorkplaceContinuityRecord(
                current.objectiveId(), current.humanId(), current.conversationRef(),
                current.ingressChannel(), current.ingressMessageRef(), current.requestAdmissionRef(),
                authorizations, meetings, decisions, evidence, current.createdAt(), at);
    }

    private static WorkplaceDelivery copyDelivery(
            WorkplaceDelivery current, WorkplaceDelivery.Status status,
            String externalRef, String failure, Instant at) {
        return new WorkplaceDelivery(
                current.deliveryId(), current.objectiveId(), current.conversationRef(), current.humanId(),
                current.channel(), current.authorizationRef(), current.payloadReference(), status,
                externalRef, failure, current.createdAt(), at);
    }

    private WorkplaceContinuityRecord requireContinuity(String objectiveId) {
        require(objectiveId, "objectiveId");
        WorkplaceContinuityRecord record = continuity.get(objectiveId);
        if (record == null) throw new IllegalArgumentException("Workplace continuity not found: " + objectiveId);
        return record;
    }

    private WorkplaceDelivery requireDelivery(String deliveryId) {
        require(deliveryId, "deliveryId");
        WorkplaceDelivery delivery = deliveries.get(deliveryId);
        if (delivery == null) throw new IllegalArgumentException("Workplace delivery not found: " + deliveryId);
        return delivery;
    }

    private static void requireHuman(WorkplaceContinuityRecord current, String humanId) {
        require(humanId, "humanId");
        if (!current.humanId().equals(humanId)) throw new SecurityException("Workplace Human identity mismatch");
    }

    private static void requireChannelAuthorization(
            WorkplaceContinuityRecord current, String channel, String authorizationRef,
            WorkplaceChannelAuthorizationVerifier.Purpose purpose) {
        require(channel, "channel");
        require(authorizationRef, "authorizationRef");
        String expected = current.channelAuthorizationRefs().get(authKey(purpose, channel));
        if (!authorizationRef.equals(expected)) {
            throw new SecurityException("Workplace channel authorization missing or stale");
        }
    }

    private static String authKey(WorkplaceChannelAuthorizationVerifier.Purpose purpose, String channel) {
        return purpose.name() + ":" + channel.trim();
    }

    private static List<String> mergeEvidence(List<String> left, List<String> right) {
        List<String> values = new ArrayList<>();
        left.stream().filter(ref -> !values.contains(ref)).forEach(values::add);
        right.stream().filter(ref -> !values.contains(ref)).forEach(values::add);
        return List.copyOf(values);
    }

    private static void addDistinct(List<String> values, String value) {
        String normalized = value.trim();
        if (!values.contains(normalized)) values.add(normalized);
    }

    private void persist() {
        store.save(new WorkplaceContinuityStateStore.Snapshot(continuity, deliveries));
    }

    private enum ReferenceType { MEETING, DECISION, EVIDENCE }

    public record ProgressProjection(
            String objectiveId,
            String ownerWorkerId,
            String objectiveStatus,
            String conversationRef,
            List<String> meetingRefs,
            List<String> decisionRefs,
            List<String> assignmentRefs,
            List<String> evidenceRefs,
            int plannedWork,
            int completedWork,
            String blocker,
            String workStatus,
            String ingressChannel,
            String queryChannel) {}

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }
}
