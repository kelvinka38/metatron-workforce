package com.metatron.workforce.operating;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.phase5.WorkSchedule;
import com.metatron.workforce.phase5.WorkScheduleService;
import com.metatron.workforce.runtime.RuntimeInstance;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Composes the SoT-required Worker relationships into one durable runtime Constitution projection.
 *
 * This class deliberately does not become a second authority for Worker identity, assignments,
 * schedules, execution attempts or runtimes. Each materialization reads the current authoritative
 * stores and persists only the resulting read model + provenance.
 */
public final class WorkerConstitutionRuntimeMaterializer {
    private final WorkforceCoreService core;
    private final WorkerConstitutionService constitution;
    private final WorkScheduleService schedules;
    private final WorkerRuntimeProfileBindingService runtimeProfiles;
    private final RuntimeRegistry runtimes;
    private final ExecutionAttemptService executionAttempts;
    private final WorkerConstitutionRuntimeStateStore store;
    private final Map<String, RuntimeConstitution> snapshots = new LinkedHashMap<>();

    public WorkerConstitutionRuntimeMaterializer(
            WorkforceCoreService core,
            WorkerConstitutionService constitution,
            WorkScheduleService schedules,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeRegistry runtimes,
            ExecutionAttemptService executionAttempts,
            WorkerConstitutionRuntimeStateStore store) {
        this.core = Objects.requireNonNull(core, "core");
        this.constitution = Objects.requireNonNull(constitution, "constitution");
        this.schedules = Objects.requireNonNull(schedules, "schedules");
        this.runtimeProfiles = Objects.requireNonNull(runtimeProfiles, "runtimeProfiles");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.executionAttempts = Objects.requireNonNull(executionAttempts, "executionAttempts");
        this.store = Objects.requireNonNull(store, "store");
        store.load().constitutions().forEach(snapshot ->
                snapshots.put(key(snapshot.workerId(), snapshot.participation().participationId()), snapshot));
    }

    public static WorkerConstitutionRuntimeMaterializer inMemory(
            WorkforceCoreService core,
            WorkerConstitutionService constitution,
            WorkScheduleService schedules,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeRegistry runtimes,
            ExecutionAttemptService executionAttempts) {
        return new WorkerConstitutionRuntimeMaterializer(
                core, constitution, schedules, runtimeProfiles, runtimes, executionAttempts,
                new WorkerConstitutionRuntimeStateStore() {
                    private Snapshot state = Snapshot.empty();
                    @Override public Snapshot load() { return state; }
                    @Override public void save(Snapshot snapshot) { state = snapshot; }
                });
    }

    public synchronized RuntimeConstitution materializeForAssignment(
            String workerId,
            String assignmentReference,
            Instant at) {
        require(workerId, "workerId");
        require(assignmentReference, "assignmentReference");
        WorkforceCoreService.Assignment assignment = core.allAssignments().stream()
                .filter(value -> assignmentReference.equals(value.assignmentId()))
                .filter(value -> workerId.equals(value.workerId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "worker-constitution-assignment-unbound:" + workerId + ":" + assignmentReference));
        return materialize(workerId, assignment.participationId(), at);
    }

    public synchronized RuntimeConstitution materialize(
            String workerId,
            String participationId,
            Instant at) {
        require(workerId, "workerId");
        require(participationId, "participationId");
        Objects.requireNonNull(at, "at");

        WorkforceCoreService.Worker worker = core.worker(workerId);
        WorkforceCoreService.Participant participant = core.allParticipants().stream()
                .filter(value -> value.participantId().equals(worker.participantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("worker-participant-missing:" + workerId));
        WorkforceCoreService.Participation participation = core.participations(workerId).stream()
                .filter(value -> value.participationId().equals(participationId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "worker-participation-missing:" + workerId + ":" + participationId));
        if (participation.status() != WorkforceCoreService.ParticipationStatus.ACTIVE) {
            throw new IllegalStateException(
                    "worker-participation-not-active:" + workerId + ":" + participation.status());
        }

        WorkerConstitutionService.ConstitutionContext standing =
                constitution.contextFor(workerId, participationId);

        List<WorkforceCoreService.Capability> capabilities = core.capabilities(workerId).stream()
                .sorted(Comparator.comparing(WorkforceCoreService.Capability::capabilityRef))
                .toList();
        List<WorkforceCoreService.Qualification> qualifications = core.qualifications(workerId).stream()
                .sorted(Comparator.comparing(WorkforceCoreService.Qualification::qualificationRef))
                .toList();
        List<WorkforceCoreService.CapacityReservation> reservations = core.allCapacityReservations().stream()
                .filter(value -> workerId.equals(value.workerId()))
                .sorted(Comparator.comparing(WorkforceCoreService.CapacityReservation::createdAt))
                .toList();
        List<WorkforceCoreService.Assignment> assignments = core.assignments(workerId).stream()
                .sorted(Comparator.comparing(WorkforceCoreService.Assignment::createdAt))
                .toList();
        List<WorkSchedule> workerSchedules = schedules.forWorker(workerId).stream()
                .sorted(Comparator.comparing(WorkSchedule::start).thenComparing(WorkSchedule::scheduleId))
                .toList();
        List<ExecutionAttempt> attempts = executionAttempts.all().stream()
                .filter(value -> workerId.equals(value.workerId()))
                .sorted(Comparator.comparing(ExecutionAttempt::updatedAt).reversed()
                        .thenComparing(ExecutionAttempt::attemptId))
                .limit(100)
                .toList();

        WorkforceCoreService.Availability availability = core.availability(workerId).orElse(null);
        double reservedCapacity = reservations.stream()
                .filter(value -> value.status() == WorkforceCoreService.ReservationStatus.ACTIVE)
                .mapToDouble(WorkforceCoreService.CapacityReservation::capacity)
                .sum();
        CapacityReality capacity = new CapacityReality(
                availability != null && availability.available(),
                availability == null ? 0.0 : availability.capacity(),
                reservedCapacity,
                core.remainingCapacity(workerId),
                availability == null ? null : availability.observedAt());

        WorkerRuntimeProfileBindingService.Binding profile = runtimeProfiles.requireBinding(workerId);
        RuntimeInstance running = runtimes.runningForWorker(workerId).orElse(null);
        RuntimeRelationship runtime = new RuntimeRelationship(
                profile.profile().profileRef(),
                profile.capabilityRef(),
                profile.boundAt(),
                profile.profile().actionRefs().stream().sorted().toList(),
                profile.profile().allowedExecutables().stream().sorted().toList(),
                profile.profile().writableWorkspace(),
                running == null ? "" : running.runtimeId(),
                running == null ? "NOT_RUNNING" : running.state().name(),
                running == null ? null : running.createdAt());

        List<String> objectiveReferences = assignments.stream()
                .map(WorkforceCoreService.Assignment::objectiveRef)
                .distinct()
                .sorted()
                .toList();

        LinkedHashSet<String> evidence = new LinkedHashSet<>(standing.evidenceReferences());
        evidence.add("worker-runtime-constitution:participant=" + participant.participantId()
                + ":type=" + participant.type());
        evidence.add("worker-runtime-constitution:worker=" + worker.workerId()
                + ":status=" + worker.status());
        evidence.add("worker-runtime-constitution:participation=" + participation.participationId()
                + ":status=" + participation.status());
        capabilities.forEach(value -> evidence.add(
                "worker-runtime-constitution:capability=" + value.capabilityRef()
                        + ":level=" + value.level() + ":evidence=" + value.evidenceRef()));
        qualifications.forEach(value -> evidence.add(
                "worker-runtime-constitution:qualification=" + value.qualificationRef()
                        + ":evidence=" + value.evidenceRef()));
        if (availability != null) {
            evidence.add("worker-runtime-constitution:availability=" + availability.available()
                    + ":capacity=" + availability.capacity() + ":observed=" + availability.observedAt());
        }
        reservations.forEach(value -> evidence.add(
                "worker-runtime-constitution:capacity-reservation=" + value.reservationId()
                        + ":assignment=" + value.assignmentId() + ":status=" + value.status()));
        assignments.forEach(value -> evidence.add(
                "worker-runtime-constitution:assignment=" + value.assignmentId()
                        + ":objective=" + value.objectiveRef()
                        + ":authority=" + value.authorityRef()
                        + ":authorization=" + value.authorizationRef()
                        + ":status=" + value.status()));
        workerSchedules.forEach(value -> evidence.add(
                "worker-runtime-constitution:schedule=" + value.scheduleId()
                        + ":assignment=" + value.assignmentRef()
                        + ":status=" + value.status()
                        + ":evidence=" + value.evidenceRef()));
        evidence.add("worker-runtime-constitution:runtime-profile=" + runtime.profileRef());
        if (!runtime.runtimeId().isBlank()) {
            evidence.add("worker-runtime-constitution:runtime=" + runtime.runtimeId()
                    + ":state=" + runtime.runtimeState());
        }
        attempts.forEach(value -> evidence.add(
                "worker-runtime-constitution:execution-attribution=" + value.attemptId()
                        + ":assignment=" + value.assignmentRef()
                        + ":authorization=" + value.authorizationRef()
                        + ":runtime=" + value.runtimeId()
                        + ":status=" + value.status()));

        String snapshotId = "worker-constitution-runtime:" + safe(workerId)
                + ":" + safe(participationId);
        String rendered = render(
                snapshotId, at, participant, worker, participation, standing,
                capabilities, qualifications, capacity, reservations, assignments,
                objectiveReferences, workerSchedules, runtime, attempts);

        RuntimeConstitution snapshot = new RuntimeConstitution(
                snapshotId,
                at,
                workerId,
                participant,
                worker,
                participation,
                standing.contract(),
                standing.binding(),
                capabilities,
                qualifications,
                capacity,
                reservations,
                assignments,
                objectiveReferences,
                workerSchedules,
                runtime,
                attempts,
                standing.performance(),
                standing.recentExperience(),
                standing.recentLearning(),
                List.of(),
                rendered,
                List.copyOf(evidence));

        snapshots.put(key(workerId, participationId), snapshot);
        persist();
        return snapshot;
    }

    public synchronized Optional<RuntimeConstitution> current(String workerId, String participationId) {
        return Optional.ofNullable(snapshots.get(key(workerId, participationId)));
    }

    public synchronized List<RuntimeConstitution> all() {
        return snapshots.values().stream()
                .sorted(Comparator.comparing(RuntimeConstitution::workerId)
                        .thenComparing(value -> value.participation().participationId()))
                .toList();
    }

    private void persist() {
        store.save(new WorkerConstitutionRuntimeStateStore.Snapshot(all()));
    }

    private static String render(
            String snapshotId,
            Instant at,
            WorkforceCoreService.Participant participant,
            WorkforceCoreService.Worker worker,
            WorkforceCoreService.Participation participation,
            WorkerConstitutionService.ConstitutionContext standing,
            List<WorkforceCoreService.Capability> capabilities,
            List<WorkforceCoreService.Qualification> qualifications,
            CapacityReality capacity,
            List<WorkforceCoreService.CapacityReservation> reservations,
            List<WorkforceCoreService.Assignment> assignments,
            List<String> objectiveReferences,
            List<WorkSchedule> schedules,
            RuntimeRelationship runtime,
            List<ExecutionAttempt> attempts) {
        return """
                MATERIALIZED WORKER CONSTITUTION — RUNTIME
                snapshot_id=%s
                materialized_at=%s

                PERSISTENT IDENTITY
                participant_id=%s
                participant_type=%s
                participant_provenance=%s
                worker_id=%s
                worker_status=%s
                worker_admitted_at=%s

                ACTIVE INSTITUTIONAL PARTICIPATION
                participation_id=%s
                organization_ref=%s
                position_ref=%s
                role_ref=%s
                participation_status=%s
                participation_started_at=%s

                STANDING POSITION / OPERATING CONTRACT
                %s

                ACTUAL CAPABILITY RELATIONSHIPS
                %s

                ACTUAL QUALIFICATION RELATIONSHIPS
                %s

                CURRENT AVAILABILITY / CAPACITY
                %s
                capacity_reservations=%s

                CURRENT / HISTORICAL ASSIGNMENT + AUTHORITY + AUTHORIZATION RELATIONSHIPS
                %s
                objective_references=%s

                WORK SCHEDULE RELATIONSHIPS
                %s

                RUNTIME RELATIONSHIP
                %s

                EXECUTION ATTRIBUTION
                %s

                PERFORMANCE / EXPERIENCE / LEARNING
                performance=%s
                recent_experience=%s
                recent_learning=%s

                REPUTATION CLAIMS
                none recorded; no reputation is inferred from role, capability, execution or provider output
                """.formatted(
                snapshotId,
                at,
                participant.participantId(),
                participant.type(),
                participant.provenanceRef(),
                worker.workerId(),
                worker.status(),
                worker.admittedAt(),
                participation.participationId(),
                participation.organizationRef(),
                participation.positionRef(),
                participation.roleRef(),
                participation.status(),
                participation.startedAt(),
                standing.renderedContext(),
                capabilities.isEmpty() ? "none recorded" : capabilities,
                qualifications.isEmpty() ? "none recorded" : qualifications,
                capacity,
                reservations.isEmpty() ? "none recorded" : reservations,
                assignments.isEmpty() ? "none recorded" : assignments,
                objectiveReferences.isEmpty() ? "none recorded" : objectiveReferences,
                schedules.isEmpty() ? "none recorded" : schedules,
                runtime,
                attempts.isEmpty() ? "none recorded" : attempts,
                standing.performance() == null ? "none recorded yet" : standing.performance(),
                standing.recentExperience().isEmpty() ? "none recorded yet" : standing.recentExperience(),
                standing.recentLearning().isEmpty() ? "none recorded yet" : standing.recentLearning());
    }

    private static String key(String workerId, String participationId) {
        require(workerId, "workerId");
        require(participationId, "participationId");
        return workerId + "|" + participationId;
    }

    private static String safe(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._:-]+", "-");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }

    public record CapacityReality(
            boolean available,
            double configuredCapacity,
            double reservedCapacity,
            double remainingCapacity,
            Instant observedAt) {}

    public record RuntimeRelationship(
            String profileRef,
            String bindingCapabilityRef,
            Instant profileBoundAt,
            List<String> actionRefs,
            List<String> allowedExecutables,
            boolean writableWorkspace,
            String runtimeId,
            String runtimeState,
            Instant runtimeCreatedAt) {
        public RuntimeRelationship {
            require(profileRef, "profileRef");
            require(bindingCapabilityRef, "bindingCapabilityRef");
            Objects.requireNonNull(profileBoundAt, "profileBoundAt");
            actionRefs = List.copyOf(actionRefs == null ? List.of() : actionRefs);
            allowedExecutables = List.copyOf(allowedExecutables == null ? List.of() : allowedExecutables);
            runtimeId = runtimeId == null ? "" : runtimeId;
            require(runtimeState, "runtimeState");
        }
    }

    public record RuntimeConstitution(
            String snapshotId,
            Instant materializedAt,
            String workerId,
            WorkforceCoreService.Participant participant,
            WorkforceCoreService.Worker worker,
            WorkforceCoreService.Participation participation,
            WorkerConstitutionService.PositionOperatingContract positionContract,
            WorkerConstitutionService.WorkerPositionBinding positionBinding,
            List<WorkforceCoreService.Capability> capabilities,
            List<WorkforceCoreService.Qualification> qualifications,
            CapacityReality capacity,
            List<WorkforceCoreService.CapacityReservation> capacityReservations,
            List<WorkforceCoreService.Assignment> assignments,
            List<String> objectiveReferences,
            List<WorkSchedule> schedules,
            RuntimeRelationship runtime,
            List<ExecutionAttempt> executionAttribution,
            WorkerConstitutionService.PerformanceEvaluation performance,
            List<WorkerConstitutionService.ExperienceRecord> recentExperience,
            List<WorkerConstitutionService.LearningRecord> recentLearning,
            List<String> reputationClaims,
            String renderedContext,
            List<String> evidenceReferences) {
        public RuntimeConstitution {
            require(snapshotId, "snapshotId");
            Objects.requireNonNull(materializedAt, "materializedAt");
            require(workerId, "workerId");
            Objects.requireNonNull(participant, "participant");
            Objects.requireNonNull(worker, "worker");
            Objects.requireNonNull(participation, "participation");
            Objects.requireNonNull(positionContract, "positionContract");
            Objects.requireNonNull(positionBinding, "positionBinding");
            capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
            qualifications = List.copyOf(qualifications == null ? List.of() : qualifications);
            Objects.requireNonNull(capacity, "capacity");
            capacityReservations = List.copyOf(capacityReservations == null ? List.of() : capacityReservations);
            assignments = List.copyOf(assignments == null ? List.of() : assignments);
            objectiveReferences = List.copyOf(objectiveReferences == null ? List.of() : objectiveReferences);
            schedules = List.copyOf(schedules == null ? List.of() : schedules);
            Objects.requireNonNull(runtime, "runtime");
            executionAttribution = List.copyOf(executionAttribution == null ? List.of() : executionAttribution);
            recentExperience = List.copyOf(recentExperience == null ? List.of() : recentExperience);
            recentLearning = List.copyOf(recentLearning == null ? List.of() : recentLearning);
            reputationClaims = List.copyOf(reputationClaims == null ? List.of() : reputationClaims);
            require(renderedContext, "renderedContext");
            evidenceReferences = List.copyOf(evidenceReferences == null ? List.of() : evidenceReferences);
        }
    }
}
