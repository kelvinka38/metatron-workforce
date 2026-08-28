package com.metatron.workforce.core;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Canonical Workforce-owned identity/participation/work relationships. External domains remain references. */
public class WorkforceCoreService {
    public enum ParticipantType { HUMAN, AI, HYBRID, EXTERNAL }
    public enum WorkerStatus { ACTIVE, SUSPENDED, RETIRED }
    public enum ParticipationStatus { ACTIVE, SUSPENDED, ENDED }
    public enum AssignmentStatus { PLANNED, ACTIVE, BLOCKED, COMPLETED, CANCELLED }

    public record Participant(String participantId, ParticipantType type, String provenanceRef, Instant recognizedAt) {}
    public record Worker(String workerId, String participantId, WorkerStatus status, Instant admittedAt) {}
    public record Participation(String participationId, String workerId, String organizationRef,
                                String positionRef, String roleRef, ParticipationStatus status, Instant startedAt) {}
    public record Capability(String workerId, String capabilityRef, double level, String evidenceRef) {}
    public record Qualification(String workerId, String qualificationRef, String evidenceRef, Instant validUntil) {}
    public record Availability(String workerId, boolean available, double capacity, Instant observedAt) {}
    public record Assignment(String assignmentId, String objectiveRef, String workerId, String participationId,
                             String authorityRef, String authorizationRef, String description,
                             AssignmentStatus status, Instant createdAt) {}

    private final Map<String, Participant> participants = new ConcurrentHashMap<>();
    private final Map<String, Worker> workers = new ConcurrentHashMap<>();
    private final Map<String, Participation> participations = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Capability>> capabilities = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Qualification>> qualifications = new ConcurrentHashMap<>();
    private final Map<String, Availability> availability = new ConcurrentHashMap<>();
    private final Map<String, Assignment> assignments = new ConcurrentHashMap<>();
    private final WorkforceCoreStateStore stateStore;

    public WorkforceCoreService() { this(new InMemoryWorkforceCoreStateStore()); }

    public WorkforceCoreService(WorkforceCoreStateStore stateStore) {
        this.stateStore = Objects.requireNonNull(stateStore);
        WorkforceCoreStateStore.Snapshot s = stateStore.load();
        participants.putAll(s.participants());
        workers.putAll(s.workers());
        participations.putAll(s.participations());
        s.capabilities().forEach((k,v) -> capabilities.put(k, new ConcurrentHashMap<>(v)));
        s.qualifications().forEach((k,v) -> qualifications.put(k, new ConcurrentHashMap<>(v)));
        availability.putAll(s.availability());
        assignments.putAll(s.assignments());
    }

    public synchronized Participant recognizeParticipant(String id, ParticipantType type, String provenanceRef) {
        require(id, "participantId"); require(provenanceRef, "provenanceRef");
        Participant existing = participants.get(id);
        if (existing != null) return existing;
        Participant created = new Participant(id, Objects.requireNonNull(type), provenanceRef, Instant.now());
        participants.put(id, created); persist(); return created;
    }

    public synchronized Worker admitWorker(String workerId, String participantId) {
        require(workerId, "workerId");
        if (!participants.containsKey(participantId)) throw new IllegalStateException("participant must be recognized before worker admission");
        Worker existing = workers.get(workerId);
        if (existing != null) return existing;
        Worker created = new Worker(workerId, participantId, WorkerStatus.ACTIVE, Instant.now());
        workers.put(workerId, created); persist(); return created;
    }

    public synchronized Worker setWorkerStatus(String workerId, WorkerStatus status) {
        Worker old = worker(workerId); Objects.requireNonNull(status);
        if (old.status() == WorkerStatus.RETIRED && status != WorkerStatus.RETIRED)
            throw new IllegalStateException("retired worker cannot be reactivated");
        Worker next = new Worker(old.workerId(), old.participantId(), status, old.admittedAt());
        workers.put(workerId, next);
        if (status != WorkerStatus.ACTIVE) availability.remove(workerId);
        persist(); return next;
    }

    public synchronized Participation participate(String id, String workerId, String organizationRef, String positionRef, String roleRef) {
        activeWorker(workerId); require(id, "participationId"); require(organizationRef, "organizationRef");
        Participation p = new Participation(id, workerId, organizationRef, positionRef, roleRef, ParticipationStatus.ACTIVE, Instant.now());
        if (participations.putIfAbsent(id, p) != null) throw new IllegalStateException("participation already exists");
        persist(); return p;
    }

    public synchronized Participation setParticipationStatus(String id, ParticipationStatus status) {
        Participation old = requireParticipation(id); Objects.requireNonNull(status);
        Participation next = new Participation(old.participationId(), old.workerId(), old.organizationRef(), old.positionRef(),
                old.roleRef(), status, old.startedAt());
        participations.put(id, next); persist(); return next;
    }

    public synchronized Capability attestCapability(String workerId, String capabilityRef, double level, String evidenceRef) {
        activeWorker(workerId); require(capabilityRef, "capabilityRef"); require(evidenceRef, "evidenceRef");
        if (!Double.isFinite(level) || level < 0) throw new IllegalArgumentException("level must be finite and non-negative");
        Capability c = new Capability(workerId, capabilityRef, level, evidenceRef);
        capabilities.computeIfAbsent(workerId, k -> new ConcurrentHashMap<>()).put(capabilityRef, c); persist(); return c;
    }

    public synchronized Qualification attestQualification(String workerId, String qualificationRef, String evidenceRef, Instant validUntil) {
        activeWorker(workerId); require(qualificationRef, "qualificationRef"); require(evidenceRef, "evidenceRef");
        Qualification q = new Qualification(workerId, qualificationRef, evidenceRef, validUntil);
        qualifications.computeIfAbsent(workerId, k -> new ConcurrentHashMap<>()).put(qualificationRef, q); persist(); return q;
    }

    public synchronized Availability setAvailability(String workerId, boolean isAvailable, double capacity) {
        activeWorker(workerId); if (!Double.isFinite(capacity) || capacity < 0) throw new IllegalArgumentException("capacity must be finite and non-negative");
        Availability a = new Availability(workerId, isAvailable, capacity, Instant.now()); availability.put(workerId, a); persist(); return a;
    }

    public synchronized Assignment assign(String assignmentId, String objectiveRef, String workerId, String participationId,
                             String authorityRef, String authorizationRef, String description) {
        activeWorker(workerId); require(assignmentId, "assignmentId"); require(objectiveRef, "objectiveRef"); require(description, "description");
        Participation p = requireParticipation(participationId);
        if (!p.workerId().equals(workerId) || p.status() != ParticipationStatus.ACTIVE) throw new IllegalStateException("active participation required");
        require(authorityRef, "authorityRef"); require(authorizationRef, "authorizationRef");
        Availability a = availability.get(workerId);
        if (a != null && (!a.available() || a.capacity() <= 0)) throw new IllegalStateException("worker has no available capacity");
        Assignment assignment = new Assignment(assignmentId, objectiveRef, workerId, participationId, authorityRef,
                authorizationRef, description, AssignmentStatus.ACTIVE, Instant.now());
        if (assignments.putIfAbsent(assignmentId, assignment) != null) throw new IllegalStateException("assignment already exists");
        persist(); return assignment;
    }

    public synchronized Assignment transitionAssignment(String assignmentId, AssignmentStatus status) {
        Assignment old = requireAssignment(assignmentId); Objects.requireNonNull(status);
        if (old.status() == AssignmentStatus.COMPLETED || old.status() == AssignmentStatus.CANCELLED)
            throw new IllegalStateException("terminal assignment cannot transition");
        Assignment next = new Assignment(old.assignmentId(), old.objectiveRef(), old.workerId(), old.participationId(),
                old.authorityRef(), old.authorizationRef(), old.description(), status, old.createdAt());
        assignments.put(assignmentId, next); persist(); return next;
    }

    public Worker worker(String id) { return Optional.ofNullable(workers.get(id)).orElseThrow(() -> new NoSuchElementException("worker not found")); }
    public List<Participant> allParticipants() { return participants.values().stream().sorted(Comparator.comparing(Participant::recognizedAt)).toList(); }
    public List<Worker> allWorkers() { return workers.values().stream().sorted(Comparator.comparing(Worker::admittedAt)).toList(); }
    public List<Participation> allParticipations() { return participations.values().stream().sorted(Comparator.comparing(Participation::startedAt)).toList(); }
    public List<Assignment> allAssignments() { return assignments.values().stream().sorted(Comparator.comparing(Assignment::createdAt)).toList(); }
    public List<Participation> participations(String workerId) { return participations.values().stream().filter(p -> p.workerId().equals(workerId)).toList(); }
    public List<Assignment> assignments(String workerId) { return assignments.values().stream().filter(a -> a.workerId().equals(workerId)).toList(); }
    public List<Capability> capabilities(String workerId) { return List.copyOf(capabilities.getOrDefault(workerId, Map.of()).values()); }
    public List<Qualification> qualifications(String workerId) { return List.copyOf(qualifications.getOrDefault(workerId, Map.of()).values()); }
    public Optional<Availability> availability(String workerId) { return Optional.ofNullable(availability.get(workerId)); }

    private Worker activeWorker(String id) { Worker w = worker(id); if (w.status() != WorkerStatus.ACTIVE) throw new IllegalStateException("worker not active"); return w; }
    private Participation requireParticipation(String id) { return Optional.ofNullable(participations.get(id)).orElseThrow(() -> new NoSuchElementException("participation not found")); }
    private Assignment requireAssignment(String id) { return Optional.ofNullable(assignments.get(id)).orElseThrow(() -> new NoSuchElementException("assignment not found")); }
    private void persist() { stateStore.save(new WorkforceCoreStateStore.Snapshot(participants, workers, participations, capabilities, qualifications, availability, assignments)); }
    private static void require(String value, String name) { if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required"); }
}
