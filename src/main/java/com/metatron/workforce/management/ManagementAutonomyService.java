package com.metatron.workforce.management;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Workforce-side coordination service for autonomous management behavior. */
public final class ManagementAutonomyService {
    private final Map<String, ManagementObjective> objectives = new LinkedHashMap<>();
    private final Map<String, List<ManagementEvent>> events = new LinkedHashMap<>();
    private final ManagementStateStore stateStore;

    public ManagementAutonomyService() { this(new InMemoryManagementStateStore()); }

    public ManagementAutonomyService(ManagementStateStore stateStore) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        ManagementStateStore.Snapshot snapshot = stateStore.load();
        objectives.putAll(snapshot.objectives());
        snapshot.events().forEach((key, value) -> events.put(key, new ArrayList<>(value)));
    }

    public synchronized ManagementObjective acceptObjective(String objectiveId, String ownerWorkerId,
            String organizationContextId, String description, Instant at) {
        return acceptObjective(objectiveId, ownerWorkerId, organizationContextId, description,
                ownerWorkerId, "AUTHORITY-COMPAT", "AUTHORIZATION-COMPAT", at);
    }

    public synchronized ManagementObjective acceptObjective(String objectiveId, String ownerWorkerId,
            String organizationContextId, String description, String initiatingActorId,
            String authorityReference, String authorizationReference, Instant at) {
        Objects.requireNonNull(at, "at"); requireText(initiatingActorId, "initiatingActorId");
        requireText(authorityReference, "authorityReference"); requireText(authorizationReference, "authorizationReference");
        if (objectives.containsKey(objectiveId)) throw new IllegalStateException("objective already exists: " + objectiveId);
        ManagementObjective objective = new ManagementObjective(objectiveId, ownerWorkerId, organizationContextId,
                description, ManagementObjective.Status.ACTIVE, List.of(), List.of(), at, at);
        objectives.put(objectiveId, objective);
        append(objectiveId, initiatingActorId, ManagementEvent.Type.OBJECTIVE_ACCEPTED,
                description + "; owner=" + ownerWorkerId + "; authority=" + authorityReference + "; authorization=" + authorizationReference, at);
        return objective;
    }

    /**
     * Accepts an authenticated/admitted Human request as a management objective without pretending
     * that the request carries institutional execution Authority or Authorization.
     */
    public synchronized ManagementObjective acceptHumanObjective(String objectiveId, String ownerWorkerId,
            String organizationContextId, String description, String humanActorId,
            String requestAdmissionReference, Instant at) {
        Objects.requireNonNull(at, "at");
        requireText(humanActorId, "humanActorId");
        requireText(requestAdmissionReference, "requestAdmissionReference");
        if (objectives.containsKey(objectiveId)) throw new IllegalStateException("objective already exists: " + objectiveId);
        ManagementObjective objective = new ManagementObjective(objectiveId, ownerWorkerId, organizationContextId,
                description, ManagementObjective.Status.ACTIVE, List.of(), List.of(), at, at);
        objectives.put(objectiveId, objective);
        append(objectiveId, humanActorId, ManagementEvent.Type.OBJECTIVE_ACCEPTED,
                description + "; owner=" + ownerWorkerId + "; request_admission=" + requestAdmissionReference
                        + "; execution_authorization=NONE", at);
        return objective;
    }

    public synchronized ManagementObjective addAssignmentReference(String objectiveId, String actorWorkerId,
            String assignmentRef, Instant at) {
        requireText(assignmentRef, "assignmentRef"); ManagementObjective current = activeObjective(objectiveId); requireOwnerOrManagerActor(current, actorWorkerId);
        List<String> refs = new ArrayList<>(current.assignmentRefs()); if (!refs.contains(assignmentRef)) refs.add(assignmentRef);
        ManagementObjective updated = copy(current, current.status(), refs, current.evidenceRefs(), at); objectives.put(objectiveId, updated);
        append(objectiveId, actorWorkerId, ManagementEvent.Type.ASSIGNMENT_REFERENCED, assignmentRef, at); return updated;
    }

    public synchronized Optional<StaffingNeed> assessCapacity(String objectiveId, String actorWorkerId,
            String requiredCapability, double requiredCapacity, double availableCapacity, Instant at) {
        ManagementObjective current = activeObjective(objectiveId); requireOwnerOrManagerActor(current, actorWorkerId); requireText(requiredCapability, "requiredCapability");
        if (requiredCapacity < 0 || availableCapacity < 0) throw new IllegalArgumentException("capacity values must be >= 0");
        double gap = requiredCapacity - availableCapacity;
        if (gap <= 0) { append(objectiveId, actorWorkerId, ManagementEvent.Type.CAPACITY_SUFFICIENT, requiredCapability + ": required=" + requiredCapacity + ", available=" + availableCapacity, at); return Optional.empty(); }
        StaffingNeed need = new StaffingNeed(objectiveId, current.ownerWorkerId(), current.organizationContextId(), requiredCapability, requiredCapacity, availableCapacity, gap, at);
        append(objectiveId, actorWorkerId, ManagementEvent.Type.STAFFING_NEED_DETECTED, requiredCapability + ": gap=" + gap, at); return Optional.of(need);
    }

    public synchronized ManagementObjective markBlocked(String objectiveId, String actorWorkerId, String reason, Instant at) {
        ManagementObjective current = activeObjective(objectiveId); requireOwnerOrManagerActor(current, actorWorkerId); requireText(reason, "reason");
        ManagementObjective updated = copy(current, ManagementObjective.Status.BLOCKED, current.assignmentRefs(), current.evidenceRefs(), at); objectives.put(objectiveId, updated);
        append(objectiveId, actorWorkerId, ManagementEvent.Type.BLOCKED, reason, at); return updated;
    }

    public synchronized ManagementObjective recoverLocally(String objectiveId, String actorWorkerId, String recoveryPlan, Instant at) {
        ManagementObjective current = getRequired(objectiveId); requireOwnerOrManagerActor(current, actorWorkerId);
        if (current.terminal()) throw new IllegalStateException("objective is terminal: " + objectiveId);
        if (current.status() != ManagementObjective.Status.BLOCKED && current.status() != ManagementObjective.Status.ESCALATED) throw new IllegalStateException("objective is not blocked/escalated: " + objectiveId);
        requireText(recoveryPlan, "recoveryPlan"); ManagementObjective updated = copy(current, ManagementObjective.Status.ACTIVE, current.assignmentRefs(), current.evidenceRefs(), at); objectives.put(objectiveId, updated);
        append(objectiveId, actorWorkerId, ManagementEvent.Type.LOCAL_RECOVERY, recoveryPlan, at); return updated;
    }

    public synchronized ManagementObjective escalate(String objectiveId, String actorWorkerId, String reason, Instant at) {
        ManagementObjective current = getRequired(objectiveId); requireOwnerOrManagerActor(current, actorWorkerId); if (current.terminal()) throw new IllegalStateException("objective is terminal: " + objectiveId);
        requireText(reason, "reason"); ManagementObjective updated = copy(current, ManagementObjective.Status.ESCALATED, current.assignmentRefs(), current.evidenceRefs(), at); objectives.put(objectiveId, updated);
        append(objectiveId, actorWorkerId, ManagementEvent.Type.ESCALATED, reason, at); return updated;
    }

    public synchronized ManagementObjective deliver(String objectiveId, String actorWorkerId, List<String> evidenceRefs, Instant at) {
        ManagementObjective current = activeObjective(objectiveId); requireOwnerOrManagerActor(current, actorWorkerId); Objects.requireNonNull(evidenceRefs, "evidenceRefs");
        List<String> normalized = evidenceRefs.stream().filter(Objects::nonNull).map(String::trim).filter(s ->!s.isBlank()).distinct().toList();
        if (normalized.isEmpty()) throw new IllegalArgumentException("delivery requires evidence");
        ManagementObjective updated = copy(current, ManagementObjective.Status.DELIVERED, current.assignmentRefs(), normalized, at); objectives.put(objectiveId, updated);
        append(objectiveId, actorWorkerId, ManagementEvent.Type.DELIVERED, String.join(",", normalized), at); return updated;
    }

    public synchronized ManagementObjective transferOwnership(String objectiveId, String actorWorkerId, String newOwnerWorkerId, Instant at) {
        ManagementObjective current = activeObjective(objectiveId); requireOwnerOrManagerActor(current, actorWorkerId); requireText(newOwnerWorkerId, "newOwnerWorkerId");
        ManagementObjective transferred = new ManagementObjective(current.objectiveId(), current.ownerWorkerId(), current.organizationContextId(), current.description(), ManagementObjective.Status.TRANSFERRED, current.assignmentRefs(), current.evidenceRefs(), current.createdAt(), at);
        objectives.put(objectiveId, transferred); append(objectiveId, actorWorkerId, ManagementEvent.Type.OWNERSHIP_TRANSFERRED, newOwnerWorkerId, at); return transferred;
    }

    public synchronized ManagementObjective get(String objectiveId) { return getRequired(objectiveId); }
    public synchronized List<ManagementObjective> allObjectives() { return objectives.values().stream().sorted(Comparator.comparing(ManagementObjective::updatedAt).reversed()).toList(); }
    public synchronized List<ManagementEvent> allEvents() { return events.values().stream().flatMap(List::stream).sorted(Comparator.comparing(ManagementEvent::occurredAt).reversed()).toList(); }
    public synchronized List<ManagementEvent> history(String objectiveId) { getRequired(objectiveId); return List.copyOf(events.getOrDefault(objectiveId, List.of())); }

    private ManagementObjective activeObjective(String objectiveId) { ManagementObjective objective = getRequired(objectiveId); if (objective.terminal()) throw new IllegalStateException("objective is terminal: " + objectiveId); return objective; }
    private ManagementObjective getRequired(String objectiveId) { requireText(objectiveId, "objectiveId"); ManagementObjective objective = objectives.get(objectiveId); if (objective == null) throw new IllegalArgumentException("unknown objective: " + objectiveId); return objective; }
    private static void requireOwnerOrManagerActor(ManagementObjective objective, String actorWorkerId) { requireText(actorWorkerId, "actorWorkerId"); if (!objective.ownerWorkerId().equals(actorWorkerId)) throw new SecurityException("management action requires objective owner in this implementation slice"); }
    private static ManagementObjective copy(ManagementObjective current, ManagementObjective.Status status, List<String> assignmentRefs, List<String> evidenceRefs, Instant at) { Objects.requireNonNull(at, "at"); return new ManagementObjective(current.objectiveId(), current.ownerWorkerId(), current.organizationContextId(), current.description(), status, assignmentRefs, evidenceRefs, current.createdAt(), at); }
    private void append(String objectiveId, String actorWorkerId, ManagementEvent.Type type, String detail, Instant at) { Objects.requireNonNull(at, "at"); events.computeIfAbsent(objectiveId, ignored -> new ArrayList<>()).add(new ManagementEvent(objectiveId, actorWorkerId, type, detail, at)); persist(); }
    private void persist() { stateStore.save(new ManagementStateStore.Snapshot(objectives, events)); }
    private static void requireText(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank"); }

    public record ManagementEvent(String objectiveId, String actorWorkerId, Type type, String detail, Instant occurredAt) {
        public ManagementEvent { requireText(objectiveId, "objectiveId"); requireText(actorWorkerId, "actorWorkerId"); Objects.requireNonNull(type, "type"); detail = detail == null ? "" : detail; Objects.requireNonNull(occurredAt, "occurredAt"); }
        public enum Type { OBJECTIVE_ACCEPTED, ASSIGNMENT_REFERENCED, CAPACITY_SUFFICIENT, STAFFING_NEED_DETECTED, BLOCKED, LOCAL_RECOVERY, ESCALATED, DELIVERED, OWNERSHIP_TRANSFERRED }
    }
}
