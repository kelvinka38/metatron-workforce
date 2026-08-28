package com.metatron.workforce.core;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Canonical Workforce-owned identity/participation/work relationships. External domains remain references. */
@Service
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

    public Participant recognizeParticipant(String id, ParticipantType type, String provenanceRef) {
        require(id, "participantId"); require(provenanceRef, "provenanceRef");
        return participants.computeIfAbsent(id, k -> new Participant(k, Objects.requireNonNull(type), provenanceRef, Instant.now()));
    }

    public Worker admitWorker(String workerId, String participantId) {
        require(workerId, "workerId");
        if (!participants.containsKey(participantId)) throw new IllegalStateException("participant must be recognized before worker admission");
        return workers.computeIfAbsent(workerId, k -> new Worker(k, participantId, WorkerStatus.ACTIVE, Instant.now()));
    }

    public Participation participate(String id, String workerId, String organizationRef, String positionRef, String roleRef) {
        activeWorker(workerId); require(organizationRef, "organizationRef");
        Participation p = new Participation(id, workerId, organizationRef, positionRef, roleRef, ParticipationStatus.ACTIVE, Instant.now());
        if (participations.putIfAbsent(id, p) != null) throw new IllegalStateException("participation already exists");
        return p;
    }

    public Capability attestCapability(String workerId, String capabilityRef, double level, String evidenceRef) {
        activeWorker(workerId); if (level < 0) throw new IllegalArgumentException("level must be non-negative");
        Capability c = new Capability(workerId, capabilityRef, level, evidenceRef);
        capabilities.computeIfAbsent(workerId, k -> new ConcurrentHashMap<>()).put(capabilityRef, c); return c;
    }

    public Qualification attestQualification(String workerId, String qualificationRef, String evidenceRef, Instant validUntil) {
        activeWorker(workerId); Qualification q = new Qualification(workerId, qualificationRef, evidenceRef, validUntil);
        qualifications.computeIfAbsent(workerId, k -> new ConcurrentHashMap<>()).put(qualificationRef, q); return q;
    }

    public Availability setAvailability(String workerId, boolean isAvailable, double capacity) {
        activeWorker(workerId); if (capacity < 0) throw new IllegalArgumentException("capacity must be non-negative");
        Availability a = new Availability(workerId, isAvailable, capacity, Instant.now()); availability.put(workerId, a); return a;
    }

    public Assignment assign(String assignmentId, String objectiveRef, String workerId, String participationId,
                             String authorityRef, String authorizationRef, String description) {
        activeWorker(workerId);
        Participation p = requireParticipation(participationId);
        if (!p.workerId().equals(workerId) || p.status() != ParticipationStatus.ACTIVE) throw new IllegalStateException("active participation required");
        require(authorityRef, "authorityRef"); require(authorizationRef, "authorizationRef");
        Assignment a = new Assignment(assignmentId, objectiveRef, workerId, participationId, authorityRef,
                authorizationRef, description, AssignmentStatus.ACTIVE, Instant.now());
        if (assignments.putIfAbsent(assignmentId, a) != null) throw new IllegalStateException("assignment already exists");
        return a;
    }

    public Assignment transitionAssignment(String assignmentId, AssignmentStatus status) {
        Assignment old = requireAssignment(assignmentId);
        if (old.status() == AssignmentStatus.COMPLETED || old.status() == AssignmentStatus.CANCELLED)
            throw new IllegalStateException("terminal assignment cannot transition");
        Assignment next = new Assignment(old.assignmentId(), old.objectiveRef(), old.workerId(), old.participationId(),
                old.authorityRef(), old.authorizationRef(), old.description(), status, old.createdAt());
        assignments.put(assignmentId, next); return next;
    }

    public Worker worker(String id) { return Optional.ofNullable(workers.get(id)).orElseThrow(() -> new NoSuchElementException("worker not found")); }
    public List<Participation> participations(String workerId) { return participations.values().stream().filter(p -> p.workerId().equals(workerId)).toList(); }
    public List<Assignment> assignments(String workerId) { return assignments.values().stream().filter(a -> a.workerId().equals(workerId)).toList(); }
    public Optional<Availability> availability(String workerId) { return Optional.ofNullable(availability.get(workerId)); }

    private Worker activeWorker(String id) { Worker w = worker(id); if (w.status() != WorkerStatus.ACTIVE) throw new IllegalStateException("worker not active"); return w; }
    private Participation requireParticipation(String id) { return Optional.ofNullable(participations.get(id)).orElseThrow(() -> new NoSuchElementException("participation not found")); }
    private Assignment requireAssignment(String id) { return Optional.ofNullable(assignments.get(id)).orElseThrow(() -> new NoSuchElementException("assignment not found")); }
    private static void require(String value, String name) { if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required"); }
}
