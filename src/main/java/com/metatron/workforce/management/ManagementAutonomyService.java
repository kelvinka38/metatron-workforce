package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Durable Workforce-side coordination service for autonomous management behavior. */
public final class ManagementAutonomyService {
    private final Map<String, ManagementObjective> objectives = new LinkedHashMap<>();
    private final Map<String, List<ManagementEvent>> events = new LinkedHashMap<>();
    private final Map<String, AutonomousObjectiveWork> objectiveWork = new LinkedHashMap<>();
    private final Map<String, ManagementLease> leases = new LinkedHashMap<>();
    private final List<ManagementOutboxMessage> outbox = new ArrayList<>();
    private final ManagementStateStore stateStore;

    public ManagementAutonomyService() {
        this(new InMemoryManagementStateStore());
    }

    public ManagementAutonomyService(ManagementStateStore stateStore) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        ManagementStateStore.Snapshot snapshot = stateStore.load();
        objectives.putAll(snapshot.objectives());
        snapshot.events().forEach((key, value) -> events.put(key, new ArrayList<>(value)));
        objectiveWork.putAll(snapshot.objectiveWork());
        leases.putAll(snapshot.leases());
        outbox.addAll(snapshot.outbox());
    }

    public synchronized ManagementObjective acceptObjective(String objectiveId, String ownerWorkerId,
            String organizationContextId, String description, Instant at) {
        return acceptObjective(objectiveId, ownerWorkerId, organizationContextId, description,
                ownerWorkerId, "AUTHORITY-COMPAT", "AUTHORIZATION-COMPAT", at);
    }

    public synchronized ManagementObjective acceptObjective(String objectiveId, String ownerWorkerId,
            String organizationContextId, String description, String initiatingActorId,
            String authorityReference, String authorizationReference, Instant at) {
        Objects.requireNonNull(at, "at");
        requireText(initiatingActorId, "initiatingActorId");
        requireText(authorityReference, "authorityReference");
        requireText(authorizationReference, "authorizationReference");
        ManagementObjective objective = newObjective(objectiveId, ownerWorkerId, organizationContextId,
                description, ManagementObjective.Status.ACTIVE, at);
        objectives.put(objectiveId, objective);
        appendUnpersisted(objectiveId, initiatingActorId, ManagementEvent.Type.OBJECTIVE_ACCEPTED,
                description + "; owner=" + ownerWorkerId + "; authority=" + authorityReference
                        + "; authorization=" + authorizationReference, at);
        addOutboxUnpersisted("ObjectiveAccepted", objectiveId, objectiveId, objectiveId,
                "objective=" + objectiveId + ";owner=" + ownerWorkerId, at);
        persist();
        return objective;
    }

    /** Compatibility acceptance for callers that do not yet provide a durable normalized request. */
    public synchronized ManagementObjective acceptHumanObjective(String objectiveId, String ownerWorkerId,
            String organizationContextId, String description, String humanActorId,
            String requestAdmissionReference, Instant at) {
        requireHumanAcceptance(objectiveId, humanActorId, requestAdmissionReference, at);
        ManagementObjective objective = newObjective(objectiveId, ownerWorkerId, organizationContextId,
                description, ManagementObjective.Status.ACTIVE, at);
        objectives.put(objectiveId, objective);
        appendHumanAcceptance(objective, humanActorId, requestAdmissionReference, at);
        addOutboxUnpersisted("ObjectiveAccepted", objectiveId, objectiveId, objectiveId,
                "objective=" + objectiveId + ";owner=" + ownerWorkerId, at);
        persist();
        return objective;
    }

    /**
     * Atomically accepts Human responsibility together with the durable background planning input
     * and acceptance outbox message. No Intelligence planning or capability execution occurs here.
     */
    public synchronized ManagementObjective acceptHumanObjective(String objectiveId, String ownerWorkerId,
            String organizationContextId, String description, String humanActorId,
            String requestAdmissionReference, String caseId, String conversationId,
            String externalMessageReference, String channel, NormalizedRequest request, Instant at) {
        requireHumanAcceptance(objectiveId, humanActorId, requestAdmissionReference, at);
        Objects.requireNonNull(request, "request");
        ManagementObjective objective = newObjective(objectiveId, ownerWorkerId, organizationContextId,
                description, ManagementObjective.Status.ACCEPTED, at);
        AutonomousObjectiveWork work = new AutonomousObjectiveWork(
                objectiveId, stripActorPrefix(humanActorId), organizationContextId,
                requireText(caseId, "caseId"), requireText(conversationId, "conversationId"),
                requireText(externalMessageReference, "externalMessageReference"),
                requireText(channel, "channel"), request, List.of(), List.of(), List.of(),
                AutonomousObjectiveWork.Status.PENDING_PLANNING, "", 1, at, at);
        objectives.put(objectiveId, objective);
        objectiveWork.put(objectiveId, work);
        appendHumanAcceptance(objective, humanActorId, requestAdmissionReference, at);
        addOutboxUnpersisted("ObjectiveAccepted", objectiveId, externalMessageReference,
                externalMessageReference, "objective=" + objectiveId + ";owner=" + ownerWorkerId, at);
        persist();
        return objective;
    }

    public synchronized Optional<ManagementLease> acquireManagementLease(
            String objectiveId, String runnerId, Duration duration, Instant at) {
        getRequired(objectiveId);
        requireText(runnerId, "runnerId");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(at, "at");
        if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("duration must be positive");
        ManagementLease current = leases.get(objectiveId);
        if (current != null && current.activeAt(at)) return Optional.empty();
        long version = current == null ? 1 : current.fencingVersion() + 1;
        ManagementLease acquired = new ManagementLease(objectiveId, runnerId,
                "management-lease:" + objectiveId + ":" + version + ":" + UUID.randomUUID(),
                version, at, at.plus(duration));
        leases.put(objectiveId, acquired);
        appendUnpersisted(objectiveId, runnerId, ManagementEvent.Type.MANAGEMENT_LEASE_ACQUIRED,
                "fencing_version=" + version, at);
        persist();
        return Optional.of(acquired);
    }

    public synchronized ManagementLease renewManagementLease(
            String objectiveId, String runnerId, String token, Duration duration, Instant at) {
        ManagementLease current = requireLease(objectiveId, runnerId, token, at);
        ManagementLease renewed = new ManagementLease(objectiveId, runnerId, token,
                current.fencingVersion(), current.acquiredAt(), at.plus(duration));
        leases.put(objectiveId, renewed);
        persist();
        return renewed;
    }

    public synchronized void releaseManagementLease(
            String objectiveId, String runnerId, String token, Instant at) {
        ManagementLease current = leases.get(objectiveId);
        if (current == null) return;
        if (!current.runnerId().equals(runnerId) || !current.token().equals(token)) {
            throw new IllegalStateException("stale management lease release: " + objectiveId);
        }
        Instant releasedAt = at.isBefore(current.acquiredAt()) ? current.acquiredAt() : at;
        leases.put(objectiveId, new ManagementLease(current.objectiveId(), current.runnerId(),
                current.token(), current.fencingVersion(), current.acquiredAt(), releasedAt));
        appendUnpersisted(objectiveId, runnerId, ManagementEvent.Type.MANAGEMENT_LEASE_RELEASED,
                "fencing_version=" + current.fencingVersion(), at);
        persist();
    }

    public synchronized AutonomousObjectiveWork beginPlanning(
            String objectiveId, String runnerId, String token, Instant at) {
        requireLease(objectiveId, runnerId, token, at);
        AutonomousObjectiveWork current = workRequired(objectiveId);
        if (current.status() != AutonomousObjectiveWork.Status.PENDING_PLANNING) return current;
        AutonomousObjectiveWork updated = copyWork(current, current.plannedWork(), current.completedStepIds(),
                current.evidenceReferences(), AutonomousObjectiveWork.Status.PLANNING, "", at);
        objectiveWork.put(objectiveId, updated);
        transitionObjectiveUnpersisted(objectiveId, ManagementObjective.Status.PLANNING, at);
        appendUnpersisted(objectiveId, runnerId, ManagementEvent.Type.PLANNING_STARTED, "", at);
        persist();
        return updated;
    }

    public synchronized AutonomousObjectiveWork recordPlan(String objectiveId, String runnerId,
            String token, List<ExecutionWorkSpec> plan, Instant at) {
        requireLease(objectiveId, runnerId, token, at);
        AutonomousObjectiveWork current = workRequired(objectiveId);
        if (current.status() != AutonomousObjectiveWork.Status.PLANNING) {
            throw new IllegalStateException("autonomous work is not planning: " + objectiveId);
        }
        List<ExecutionWorkSpec> normalized = List.copyOf(Objects.requireNonNull(plan, "plan"));
        if (normalized.isEmpty()) throw new IllegalArgumentException("plan must not be empty");
        validateWorkGraph(normalized);
        AutonomousObjectiveWork updated = copyWork(current, normalized, current.completedStepIds(),
                current.evidenceReferences(), AutonomousObjectiveWork.Status.READY, "", at);
        objectiveWork.put(objectiveId, updated);
        transitionObjectiveUnpersisted(objectiveId, ManagementObjective.Status.READY, at);
        appendUnpersisted(objectiveId, runnerId, ManagementEvent.Type.PLAN_RECORDED,
                "steps=" + normalized.size() + ";work_version=" + updated.version(), at);
        addOutboxUnpersisted("WorkPlanReady", objectiveId, objectiveId, objectiveId,
                "objective=" + objectiveId + ";steps=" + normalized.size(), at);
        persist();
        return updated;
    }

    public synchronized AutonomousObjectiveWork beginExecution(
            String objectiveId, String runnerId, String token, Instant at) {
        requireLease(objectiveId, runnerId, token, at);
        AutonomousObjectiveWork current = workRequired(objectiveId);
        if (current.status() != AutonomousObjectiveWork.Status.READY
                && current.status() != AutonomousObjectiveWork.Status.EXECUTING) {
            throw new IllegalStateException("autonomous work is not ready: " + objectiveId);
        }
        AutonomousObjectiveWork updated = copyWork(current, current.plannedWork(), current.completedStepIds(),
                current.evidenceReferences(), AutonomousObjectiveWork.Status.EXECUTING, "", at);
        objectiveWork.put(objectiveId, updated);
        transitionObjectiveUnpersisted(objectiveId, ManagementObjective.Status.EXECUTING, at);
        appendUnpersisted(objectiveId, runnerId, ManagementEvent.Type.EXECUTION_STARTED, "", at);
        persist();
        return updated;
    }

    public synchronized AutonomousObjectiveWork recordStepCompleted(String objectiveId, String runnerId,
            String token, String stepId, List<String> evidenceRefs, Instant at) {
        requireLease(objectiveId, runnerId, token, at);
        requireText(stepId, "stepId");
        AutonomousObjectiveWork current = workRequired(objectiveId);
        if (current.status() != AutonomousObjectiveWork.Status.EXECUTING) {
            throw new IllegalStateException("autonomous work is not executing: " + objectiveId);
        }
        ExecutionWorkSpec step = current.plannedWork().stream()
                .filter(candidate -> candidate.stepId().equals(stepId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown planned Work step: " + stepId));
        if (!current.completedStepIds().containsAll(step.dependsOn())) {
            throw new IllegalStateException("Work dependencies are not complete: " + stepId);
        }
        if (current.completedStepIds().contains(stepId)) return current;
        List<String> completed = new ArrayList<>(current.completedStepIds());
        completed.add(stepId);
        List<String> evidence = new ArrayList<>(current.evidenceReferences());
        Objects.requireNonNull(evidenceRefs, "evidenceRefs").stream()
                .filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank())
                .filter(value -> !evidence.contains(value)).forEach(evidence::add);
        AutonomousObjectiveWork updated = copyWork(current, current.plannedWork(), completed, evidence,
                AutonomousObjectiveWork.Status.EXECUTING, "", at);
        objectiveWork.put(objectiveId, updated);
        appendUnpersisted(objectiveId, runnerId, ManagementEvent.Type.WORK_STEP_COMPLETED,
                stepId + ";work_version=" + updated.version(), at);
        persist();
        return updated;
    }

    public synchronized ManagementObjective completeAutonomousObjective(
            String objectiveId, String runnerId, String token, Instant at) {
        requireLease(objectiveId, runnerId, token, at);
        AutonomousObjectiveWork current = workRequired(objectiveId);
        if (current.status() != AutonomousObjectiveWork.Status.EXECUTING) {
            throw new IllegalStateException("autonomous work is not executing: " + objectiveId);
        }
        if (current.evidenceReferences().isEmpty()) throw new IllegalArgumentException("completion requires evidence");
        if (current.completedStepIds().size() != current.plannedWork().size()) {
            throw new IllegalStateException("not all planned Work is complete: " + objectiveId);
        }
        AutonomousObjectiveWork completed = copyWork(current, current.plannedWork(), current.completedStepIds(),
                current.evidenceReferences(), AutonomousObjectiveWork.Status.COMPLETED, "", at);
        objectiveWork.put(objectiveId, completed);
        ManagementObjective objective = copy(getRequired(objectiveId), ManagementObjective.Status.COMPLETED,
                getRequired(objectiveId).assignmentRefs(), completed.evidenceReferences(), at);
        objectives.put(objectiveId, objective);
        appendUnpersisted(objectiveId, runnerId, ManagementEvent.Type.COMPLETED,
                String.join(",", completed.evidenceReferences()), at);
        addOutboxUnpersisted("ObjectiveCompleted", objectiveId, objectiveId, objectiveId,
                "objective=" + objectiveId + ";evidence=" + completed.evidenceReferences().size(), at);
        persist();
        return objective;
    }

    public synchronized ManagementObjective blockAutonomousObjective(String objectiveId, String runnerId,
            String token, String reason, Instant at) {
        requireLease(objectiveId, runnerId, token, at);
        requireText(reason, "reason");
        AutonomousObjectiveWork current = workRequired(objectiveId);
        objectiveWork.put(objectiveId, copyWork(current, current.plannedWork(), current.completedStepIds(),
                current.evidenceReferences(), AutonomousObjectiveWork.Status.BLOCKED, reason, at));
        ManagementObjective objective = transitionObjectiveUnpersisted(
                objectiveId, ManagementObjective.Status.BLOCKED, at);
        appendUnpersisted(objectiveId, runnerId, ManagementEvent.Type.BLOCKED, reason, at);
        addOutboxUnpersisted("ObjectiveBlocked", objectiveId, objectiveId, objectiveId,
                "objective=" + objectiveId + ";reason=" + reason, at);
        persist();
        return objective;
    }

    public synchronized ManagementObjective addAssignmentReference(String objectiveId, String actorWorkerId,
            String assignmentRef, Instant at) {
        requireText(assignmentRef, "assignmentRef");
        ManagementObjective current = activeObjective(objectiveId);
        requireOwnerOrManagerActor(current, actorWorkerId);
        List<String> refs = new ArrayList<>(current.assignmentRefs());
        if (!refs.contains(assignmentRef)) refs.add(assignmentRef);
        ManagementObjective updated = copy(current, current.status(), refs, current.evidenceRefs(), at);
        objectives.put(objectiveId, updated);
        appendUnpersisted(objectiveId, actorWorkerId, ManagementEvent.Type.ASSIGNMENT_REFERENCED, assignmentRef, at);
        persist();
        return updated;
    }

    public synchronized Optional<StaffingNeed> assessCapacity(String objectiveId, String actorWorkerId,
            String requiredCapability, double requiredCapacity, double availableCapacity, Instant at) {
        ManagementObjective current = activeObjective(objectiveId);
        requireOwnerOrManagerActor(current, actorWorkerId);
        requireText(requiredCapability, "requiredCapability");
        if (requiredCapacity < 0 || availableCapacity < 0) {
            throw new IllegalArgumentException("capacity values must be >= 0");
        }
        double gap = requiredCapacity - availableCapacity;
        if (gap <= 0) {
            appendUnpersisted(objectiveId, actorWorkerId, ManagementEvent.Type.CAPACITY_SUFFICIENT,
                    requiredCapability + ": required=" + requiredCapacity + ", available=" + availableCapacity, at);
            persist();
            return Optional.empty();
        }
        StaffingNeed need = new StaffingNeed(objectiveId, current.ownerWorkerId(),
                current.organizationContextId(), requiredCapability, requiredCapacity,
                availableCapacity, gap, at);
        appendUnpersisted(objectiveId, actorWorkerId, ManagementEvent.Type.STAFFING_NEED_DETECTED,
                requiredCapability + ": gap=" + gap, at);
        persist();
        return Optional.of(need);
    }

    public synchronized ManagementObjective markBlocked(
            String objectiveId, String actorWorkerId, String reason, Instant at) {
        ManagementObjective current = activeObjective(objectiveId);
        requireOwnerOrManagerActor(current, actorWorkerId);
        requireText(reason, "reason");
        ManagementObjective updated = copy(current, ManagementObjective.Status.BLOCKED,
                current.assignmentRefs(), current.evidenceRefs(), at);
        objectives.put(objectiveId, updated);
        AutonomousObjectiveWork work = objectiveWork.get(objectiveId);
        if (work != null && !work.terminal()) {
            objectiveWork.put(objectiveId, copyWork(work, work.plannedWork(), work.completedStepIds(),
                    work.evidenceReferences(), AutonomousObjectiveWork.Status.BLOCKED, reason, at));
        }
        appendUnpersisted(objectiveId, actorWorkerId, ManagementEvent.Type.BLOCKED, reason, at);
        persist();
        return updated;
    }

    public synchronized ManagementObjective recoverLocally(
            String objectiveId, String actorWorkerId, String recoveryPlan, Instant at) {
        ManagementObjective current = getRequired(objectiveId);
        requireOwnerOrManagerActor(current, actorWorkerId);
        if (current.terminal()) throw new IllegalStateException("objective is terminal: " + objectiveId);
        if (current.status() != ManagementObjective.Status.BLOCKED
                && current.status() != ManagementObjective.Status.ESCALATED) {
            throw new IllegalStateException("objective is not blocked/escalated: " + objectiveId);
        }
        requireText(recoveryPlan, "recoveryPlan");
        AutonomousObjectiveWork work = objectiveWork.get(objectiveId);
        ManagementObjective.Status nextStatus = ManagementObjective.Status.ACTIVE;
        if (work != null) {
            AutonomousObjectiveWork.Status workStatus = work.plannedWork().isEmpty()
                    ? AutonomousObjectiveWork.Status.PENDING_PLANNING : AutonomousObjectiveWork.Status.READY;
            objectiveWork.put(objectiveId, copyWork(work, work.plannedWork(), work.completedStepIds(),
                    work.evidenceReferences(), workStatus, "", at));
            nextStatus = work.plannedWork().isEmpty()
                    ? ManagementObjective.Status.ACCEPTED : ManagementObjective.Status.READY;
        }
        ManagementObjective updated = copy(current, nextStatus,
                current.assignmentRefs(), current.evidenceRefs(), at);
        objectives.put(objectiveId, updated);
        appendUnpersisted(objectiveId, actorWorkerId, ManagementEvent.Type.LOCAL_RECOVERY, recoveryPlan, at);
        persist();
        return updated;
    }

    public synchronized ManagementObjective escalate(
            String objectiveId, String actorWorkerId, String reason, Instant at) {
        ManagementObjective current = getRequired(objectiveId);
        requireOwnerOrManagerActor(current, actorWorkerId);
        if (current.terminal()) throw new IllegalStateException("objective is terminal: " + objectiveId);
        requireText(reason, "reason");
        ManagementObjective updated = copy(current, ManagementObjective.Status.ESCALATED,
                current.assignmentRefs(), current.evidenceRefs(), at);
        objectives.put(objectiveId, updated);
        appendUnpersisted(objectiveId, actorWorkerId, ManagementEvent.Type.ESCALATED, reason, at);
        persist();
        return updated;
    }

    /** Compatibility delivery path retained for bounded legacy callers. */
    public synchronized ManagementObjective deliver(
            String objectiveId, String actorWorkerId, List<String> evidenceRefs, Instant at) {
        ManagementObjective current = activeObjective(objectiveId);
        requireOwnerOrManagerActor(current, actorWorkerId);
        List<String> normalized = normalizeEvidence(evidenceRefs);
        ManagementObjective updated = copy(current, ManagementObjective.Status.DELIVERED,
                current.assignmentRefs(), normalized, at);
        objectives.put(objectiveId, updated);
        appendUnpersisted(objectiveId, actorWorkerId, ManagementEvent.Type.DELIVERED,
                String.join(",", normalized), at);
        persist();
        return updated;
    }

    public synchronized ManagementObjective transferOwnership(
            String objectiveId, String actorWorkerId, String newOwnerWorkerId, Instant at) {
        ManagementObjective current = activeObjective(objectiveId);
        requireOwnerOrManagerActor(current, actorWorkerId);
        requireText(newOwnerWorkerId, "newOwnerWorkerId");
        ManagementObjective transferred = new ManagementObjective(current.objectiveId(), newOwnerWorkerId,
                current.organizationContextId(), current.description(), current.status(), current.assignmentRefs(),
                current.evidenceRefs(), current.createdAt(), at);
        objectives.put(objectiveId, transferred);
        ManagementLease lease = leases.get(objectiveId);
        if (lease != null) {
            Instant releasedAt = at.isBefore(lease.acquiredAt()) ? lease.acquiredAt() : at;
            leases.put(objectiveId, new ManagementLease(lease.objectiveId(), lease.runnerId(),
                    lease.token(), lease.fencingVersion(), lease.acquiredAt(), releasedAt));
        }
        appendUnpersisted(objectiveId, actorWorkerId, ManagementEvent.Type.OWNERSHIP_TRANSFERRED,
                "from=" + current.ownerWorkerId() + ";to=" + newOwnerWorkerId, at);
        persist();
        return transferred;
    }

    public synchronized ManagementObjective get(String objectiveId) {
        return getRequired(objectiveId);
    }

    public synchronized Optional<AutonomousObjectiveWork> findAutonomousWork(String objectiveId) {
        requireText(objectiveId, "objectiveId");
        return Optional.ofNullable(objectiveWork.get(objectiveId));
    }

    public synchronized List<AutonomousObjectiveWork> runnableAutonomousWork() {
        return objectiveWork.values().stream()
                .filter(work -> !work.terminal() && work.status() != AutonomousObjectiveWork.Status.BLOCKED)
                .sorted(Comparator.comparing(AutonomousObjectiveWork::createdAt))
                .toList();
    }

    public synchronized List<ManagementObjective> allObjectives() {
        return objectives.values().stream()
                .sorted(Comparator.comparing(ManagementObjective::updatedAt).reversed()).toList();
    }

    public synchronized List<ManagementEvent> allEvents() {
        return events.values().stream().flatMap(List::stream)
                .sorted(Comparator.comparing(ManagementEvent::occurredAt).reversed()).toList();
    }

    public synchronized List<ManagementEvent> history(String objectiveId) {
        getRequired(objectiveId);
        return List.copyOf(events.getOrDefault(objectiveId, List.of()));
    }

    public synchronized List<ManagementOutboxMessage> outbox() {
        return List.copyOf(outbox);
    }

    private void requireHumanAcceptance(String objectiveId, String humanActorId,
            String requestAdmissionReference, Instant at) {
        Objects.requireNonNull(at, "at");
        requireText(objectiveId, "objectiveId");
        requireText(humanActorId, "humanActorId");
        requireText(requestAdmissionReference, "requestAdmissionReference");
        if (objectives.containsKey(objectiveId)) {
            throw new IllegalStateException("objective already exists: " + objectiveId);
        }
    }

    private ManagementObjective newObjective(String objectiveId, String ownerWorkerId,
            String organizationContextId, String description, ManagementObjective.Status status, Instant at) {
        requireText(objectiveId, "objectiveId");
        if (objectives.containsKey(objectiveId)) throw new IllegalStateException("objective already exists: " + objectiveId);
        return new ManagementObjective(objectiveId, ownerWorkerId, organizationContextId,
                description, status, List.of(), List.of(), at, at);
    }

    private void appendHumanAcceptance(ManagementObjective objective, String humanActorId,
            String requestAdmissionReference, Instant at) {
        appendUnpersisted(objective.objectiveId(), humanActorId, ManagementEvent.Type.OBJECTIVE_ACCEPTED,
                objective.description() + "; owner=" + objective.ownerWorkerId()
                        + "; request_admission=" + requestAdmissionReference
                        + "; execution_authorization=NONE", at);
    }

    private ManagementLease requireLease(String objectiveId, String runnerId, String token, Instant at) {
        ManagementLease lease = leases.get(objectiveId);
        if (lease == null || !lease.runnerId().equals(runnerId) || !lease.token().equals(token)
                || !lease.activeAt(at)) {
            throw new IllegalStateException("missing, expired, or stale management lease: " + objectiveId);
        }
        return lease;
    }

    private ManagementObjective activeObjective(String objectiveId) {
        ManagementObjective objective = getRequired(objectiveId);
        if (objective.terminal()) throw new IllegalStateException("objective is terminal: " + objectiveId);
        return objective;
    }

    private ManagementObjective getRequired(String objectiveId) {
        requireText(objectiveId, "objectiveId");
        ManagementObjective objective = objectives.get(objectiveId);
        if (objective == null) throw new IllegalArgumentException("unknown objective: " + objectiveId);
        return objective;
    }

    private AutonomousObjectiveWork workRequired(String objectiveId) {
        AutonomousObjectiveWork work = objectiveWork.get(objectiveId);
        if (work == null) throw new IllegalArgumentException("objective has no autonomous work context: " + objectiveId);
        return work;
    }

    private static void requireOwnerOrManagerActor(ManagementObjective objective, String actorWorkerId) {
        requireText(actorWorkerId, "actorWorkerId");
        if (!objective.ownerWorkerId().equals(actorWorkerId)) {
            throw new SecurityException("management action requires objective owner in this implementation slice");
        }
    }

    private ManagementObjective transitionObjectiveUnpersisted(
            String objectiveId, ManagementObjective.Status status, Instant at) {
        ManagementObjective current = getRequired(objectiveId);
        ManagementObjective updated = copy(current, status,
                current.assignmentRefs(), current.evidenceRefs(), at);
        objectives.put(objectiveId, updated);
        return updated;
    }

    private static ManagementObjective copy(ManagementObjective current, ManagementObjective.Status status,
            List<String> assignmentRefs, List<String> evidenceRefs, Instant at) {
        Objects.requireNonNull(at, "at");
        return new ManagementObjective(current.objectiveId(), current.ownerWorkerId(),
                current.organizationContextId(), current.description(), status,
                assignmentRefs, evidenceRefs, current.createdAt(), at);
    }

    private static AutonomousObjectiveWork copyWork(AutonomousObjectiveWork current,
            List<ExecutionWorkSpec> plannedWork, List<String> completedStepIds,
            List<String> evidenceRefs, AutonomousObjectiveWork.Status status,
            String blocker, Instant at) {
        return new AutonomousObjectiveWork(current.objectiveId(), current.humanId(),
                current.organizationContextId(), current.caseId(), current.conversationId(),
                current.externalMessageReference(), current.channel(), current.normalizedRequest(),
                plannedWork, completedStepIds, evidenceRefs, status, blocker,
                current.version() + 1, current.createdAt(), at);
    }

    private static List<String> normalizeEvidence(List<String> evidenceRefs) {
        Objects.requireNonNull(evidenceRefs, "evidenceRefs");
        List<String> normalized = evidenceRefs.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
        if (normalized.isEmpty()) throw new IllegalArgumentException("delivery requires evidence");
        return normalized;
    }

    private static void validateWorkGraph(List<ExecutionWorkSpec> plan) {
        Map<String, ExecutionWorkSpec> byId = new LinkedHashMap<>();
        for (ExecutionWorkSpec step : plan) {
            Objects.requireNonNull(step, "plan step");
            if (byId.putIfAbsent(step.stepId(), step) != null) {
                throw new IllegalArgumentException("duplicate Work step id: " + step.stepId());
            }
        }
        for (ExecutionWorkSpec step : plan) {
            for (String dependency : step.dependsOn()) {
                if (!byId.containsKey(dependency)) {
                    throw new IllegalArgumentException("unknown Work dependency: " + dependency);
                }
                if (dependency.equals(step.stepId())) {
                    throw new IllegalArgumentException("Work step cannot depend on itself: " + step.stepId());
                }
            }
        }
        Set<String> resolved = new LinkedHashSet<>();
        boolean progressed;
        do {
            progressed = false;
            for (ExecutionWorkSpec step : plan) {
                if (!resolved.contains(step.stepId()) && resolved.containsAll(step.dependsOn())) {
                    resolved.add(step.stepId());
                    progressed = true;
                }
            }
        } while (progressed);
        if (resolved.size() != plan.size()) {
            throw new IllegalArgumentException("Work Graph contains a dependency cycle");
        }
    }

    private void addOutboxUnpersisted(String type, String subjectId, String correlationId,
            String idempotencyKey, String payload, Instant at) {
        String messageId = "management-outbox:" + subjectId + ":" + type + ":" + outbox.size();
        outbox.add(new ManagementOutboxMessage(messageId, type, subjectId,
                correlationId, "", idempotencyKey, payload,
                ManagementOutboxMessage.Status.PENDING, at));
    }

    private void appendUnpersisted(String objectiveId, String actorWorkerId,
            ManagementEvent.Type type, String detail, Instant at) {
        Objects.requireNonNull(at, "at");
        events.computeIfAbsent(objectiveId, ignored -> new ArrayList<>())
                .add(new ManagementEvent(objectiveId, actorWorkerId, type, detail, at));
    }

    private void persist() {
        stateStore.save(new ManagementStateStore.Snapshot(
                objectives, events, objectiveWork, leases, outbox));
    }

    private static String stripActorPrefix(String actorId) {
        int separator = actorId.indexOf(':');
        return separator < 0 ? actorId : actorId.substring(separator + 1);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record ManagementEvent(
            String objectiveId, String actorWorkerId, Type type, String detail, Instant occurredAt) {
        public ManagementEvent {
            requireText(objectiveId, "objectiveId");
            requireText(actorWorkerId, "actorWorkerId");
            Objects.requireNonNull(type, "type");
            detail = detail == null ? "" : detail;
            Objects.requireNonNull(occurredAt, "occurredAt");
        }

        public enum Type {
            OBJECTIVE_ACCEPTED,
            ASSIGNMENT_REFERENCED,
            CAPACITY_SUFFICIENT,
            STAFFING_NEED_DETECTED,
            MANAGEMENT_LEASE_ACQUIRED,
            MANAGEMENT_LEASE_RELEASED,
            PLANNING_STARTED,
            PLAN_RECORDED,
            EXECUTION_STARTED,
            WORK_STEP_COMPLETED,
            BLOCKED,
            LOCAL_RECOVERY,
            ESCALATED,
            COMPLETED,
            DELIVERED,
            OWNERSHIP_TRANSFERRED
        }
    }
}
