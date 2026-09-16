package com.metatron.workforce.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Canonical Workforce-owned identity/participation/work relationships. External domains remain references. */
public class WorkforceCoreService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WorkforceCoreService.class);
    public enum ParticipantType { HUMAN, AI, HYBRID, EXTERNAL }
    public enum WorkerStatus { ACTIVE, SUSPENDED, RETIRED }
    public enum ParticipationStatus { ACTIVE, SUSPENDED, ENDED }
    public enum AssignmentStatus { PLANNED, ACTIVE, BLOCKED, COMPLETED, CANCELLED }
    public enum ReservationStatus { ACTIVE, RELEASED }

    public record Participant(String participantId, ParticipantType type, String provenanceRef, Instant recognizedAt) {}
    public record Worker(String workerId, String participantId, WorkerStatus status, Instant admittedAt) {}
    public record Participation(String participationId, String workerId, String organizationRef,
                                String positionRef, String roleRef, ParticipationStatus status, Instant startedAt) {}
    public record Capability(String workerId, String capabilityRef, double level, String evidenceRef) {}
    public record Qualification(String workerId, String qualificationRef, String evidenceRef, Instant validUntil) {}
    public record Availability(String workerId, boolean available, double capacity, Instant observedAt) {}
    public record Assignment(String assignmentId, String objectiveRef, String workerId, String participationId,
                             String authorityRef, String authorizationRef, String description,
                             AssignmentStatus status, Instant createdAt, CompletionPolicy completionPolicy) {}
    public record CapacityReservation(String reservationId, String assignmentId, String objectiveRef,
                                      String workerId, double capacity, ReservationStatus status,
                                      Instant createdAt, Instant releasedAt, String releaseReason) {}

    private final Map<String, Participant> participants = new ConcurrentHashMap<>();
    private final Map<String, Worker> workers = new ConcurrentHashMap<>();
    private final Map<String, Participation> participations = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Capability>> capabilities = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Qualification>> qualifications = new ConcurrentHashMap<>();
    private final Map<String, Availability> availability = new ConcurrentHashMap<>();
    private final Map<String, Assignment> assignments = new ConcurrentHashMap<>();
    private final Map<String, CapacityReservation> capacityReservations = new ConcurrentHashMap<>();
    private final WorkforceCoreStateStore stateStore;
    private final Clock clock;
    private final CompletionEvidenceGate completionGate;

    public WorkforceCoreService() { this(new InMemoryWorkforceCoreStateStore(), Clock.systemUTC()); }

    public WorkforceCoreService(WorkforceCoreStateStore stateStore) { this(stateStore, Clock.systemUTC()); }

    /** Wires an authoritative CompletionEvidenceGate (e.g. ReleaseEvidenceCompletionGate) at the transition chokepoint. */
    public WorkforceCoreService(WorkforceCoreStateStore stateStore, CompletionEvidenceGate completionGate) {
        this(stateStore, Clock.systemUTC(), completionGate);
    }

    WorkforceCoreService(WorkforceCoreStateStore stateStore, Clock clock) {
        this(stateStore, clock, CompletionEvidenceGate.DENY_NON_EXECUTION);
    }

    WorkforceCoreService(WorkforceCoreStateStore stateStore, Clock clock, CompletionEvidenceGate completionGate) {
        this.stateStore = Objects.requireNonNull(stateStore);
        this.clock = Objects.requireNonNull(clock);
        this.completionGate = Objects.requireNonNull(completionGate);
        WorkforceCoreStateStore.Snapshot s = stateStore.load();
        participants.putAll(s.participants());
        workers.putAll(s.workers());
        participations.putAll(s.participations());
        s.capabilities().forEach((k,v) -> capabilities.put(k, new ConcurrentHashMap<>(v)));
        s.qualifications().forEach((k,v) -> qualifications.put(k, new ConcurrentHashMap<>(v)));
        availability.putAll(s.availability());
        assignments.putAll(s.assignments());
        capacityReservations.putAll(s.capacityReservations());
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
        if (status != WorkerStatus.ACTIVE && reservedCapacity(workerId) > 0)
            throw new IllegalStateException("worker with active capacity reservations cannot be suspended or retired");
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
        if (status != ParticipationStatus.ACTIVE && reservedCapacity(old.workerId()) > 0)
            throw new IllegalStateException("participation cannot be suspended while worker has active reservations");
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
        double reserved = reservedCapacity(workerId);
        if ((!isAvailable && reserved > 0) || capacity + 1e-9 < reserved)
            throw new IllegalStateException("availability cannot invalidate active capacity reservations");
        Availability a = new Availability(workerId, isAvailable, capacity, Instant.now()); availability.put(workerId, a); persist(); return a;
    }

    /**
     * Durable finite-capacity reservation. Reservation identity is idempotent; conflicting replay fails closed.
     */
    public synchronized CapacityReservation reserveCapacity(String reservationId, String assignmentId,
            String objectiveRef, String workerId, double capacity) {
        require(reservationId, "reservationId"); require(assignmentId, "assignmentId");
        require(objectiveRef, "objectiveRef"); activeWorker(workerId);
        if (!Double.isFinite(capacity) || capacity <= 0) throw new IllegalArgumentException("capacity must be finite and positive");
        CapacityReservation existing = capacityReservations.get(reservationId);
        if (existing != null) {
            if (!existing.assignmentId().equals(assignmentId) || !existing.objectiveRef().equals(objectiveRef)
                    || !existing.workerId().equals(workerId) || Double.compare(existing.capacity(), capacity) != 0)
                throw new IllegalStateException("capacity reservation idempotency conflict");
            return existing;
        }
        Availability a = availability.get(workerId);
        if (a == null || !a.available()) throw new IllegalStateException("worker availability required before reservation");
        if (remainingCapacity(workerId) + 1e-9 < capacity) throw new IllegalStateException("insufficient worker capacity");
        CapacityReservation created = new CapacityReservation(reservationId, assignmentId, objectiveRef,
                workerId, capacity, ReservationStatus.ACTIVE, clock.instant(), null, null);
        capacityReservations.put(reservationId, created); persist(); return created;
    }

    public synchronized CapacityReservation releaseCapacity(String reservationId) {
        return releaseCapacity(reservationId, "explicit-release", clock.instant());
    }

    private CapacityReservation releaseCapacity(String reservationId, String reason, Instant releasedAt) {
        CapacityReservation old = requireReservation(reservationId);
        if (old.status() == ReservationStatus.RELEASED) return old;
        CapacityReservation next = new CapacityReservation(old.reservationId(), old.assignmentId(), old.objectiveRef(),
                old.workerId(), old.capacity(), ReservationStatus.RELEASED, old.createdAt(), releasedAt, reason);
        capacityReservations.put(reservationId, next); persist(); return next;
    }

    /** Releases only terminal-assignment reservations and aged reservations with no assignment. */
    public synchronized List<CapacityReservation> reconcileStaleCapacityReservations(Instant at, Duration orphanGrace) {
        Objects.requireNonNull(at, "at"); Objects.requireNonNull(orphanGrace, "orphanGrace");
        if (orphanGrace.isNegative()) throw new IllegalArgumentException("orphanGrace must not be negative");
        List<CapacityReservation> released = new ArrayList<>();
        for (CapacityReservation reservation : new ArrayList<>(capacityReservations.values())) {
            if (reservation.status() != ReservationStatus.ACTIVE) continue;
            Assignment assignment = assignments.get(reservation.assignmentId());
            String reason = null;
            if (assignment == null && !reservation.createdAt().plus(orphanGrace).isAfter(at)) {
                reason = "orphaned-reservation-age-exceeded";
            } else if (assignment != null && (assignment.status() == AssignmentStatus.COMPLETED
                    || assignment.status() == AssignmentStatus.CANCELLED)) {
                reason = "terminal-assignment:" + assignment.status();
            }
            if (reason == null) continue;
            CapacityReservation reconciled = releaseCapacity(reservation.reservationId(), reason, at);
            released.add(reconciled);
            LOG.warn("capacity reservation reconciled worker_id={} reservation_id={} age_seconds={} reason={}",
                    reservation.workerId(), reservation.reservationId(),
                    Math.max(0, Duration.between(reservation.createdAt(), at).toSeconds()), reason);
        }
        return List.copyOf(released);
    }

    public synchronized Assignment assignReserved(String reservationId, String participationId,
            String authorityRef, String authorizationRef, String description) {
        return assignReserved(reservationId, participationId, authorityRef, authorizationRef, description,
                CompletionPolicy.EXECUTION_REQUIRED);
    }

    public synchronized Assignment assignReserved(String reservationId, String participationId,
            String authorityRef, String authorizationRef, String description, CompletionPolicy completionPolicy) {
        CapacityReservation reservation = requireReservation(reservationId);
        if (reservation.status() != ReservationStatus.ACTIVE) throw new IllegalStateException("active capacity reservation required");
        Assignment existing = assignments.get(reservation.assignmentId());
        if (existing != null) {
            if (!existing.objectiveRef().equals(reservation.objectiveRef()) || !existing.workerId().equals(reservation.workerId()))
                throw new IllegalStateException("assignment idempotency conflict");
            return existing;
        }
        return assign(reservation.assignmentId(), reservation.objectiveRef(), reservation.workerId(), participationId,
                authorityRef, authorizationRef, description, completionPolicy);
    }

    public synchronized Assignment assign(String assignmentId, String objectiveRef, String workerId, String participationId,
                             String authorityRef, String authorizationRef, String description) {
        return assign(assignmentId, objectiveRef, workerId, participationId, authorityRef, authorizationRef, description,
                CompletionPolicy.EXECUTION_REQUIRED);
    }

    /**
     * Establishes the Assignment's CompletionPolicy at creation time. The policy is immutable for the
     * life of the Assignment: there is no setter, and transitionAssignment() is the only way status can
     * change, so nothing after this call -- Worker-initiated or otherwise -- can downgrade it. The
     * completionPolicy field is carried on the persisted Assignment record itself, so it survives the
     * existing state-store persistence/reload path with no separate storage needed.
     */
    public synchronized Assignment assign(String assignmentId, String objectiveRef, String workerId, String participationId,
                             String authorityRef, String authorizationRef, String description, CompletionPolicy completionPolicy) {
        activeWorker(workerId); require(assignmentId, "assignmentId"); require(objectiveRef, "objectiveRef"); require(description, "description");
        Objects.requireNonNull(completionPolicy, "completionPolicy");
        Participation p = requireParticipation(participationId);
        if (!p.workerId().equals(workerId) || p.status() != ParticipationStatus.ACTIVE) throw new IllegalStateException("active participation required");
        require(authorityRef, "authorityRef"); require(authorizationRef, "authorizationRef");
        Availability a = availability.get(workerId);
        if (a != null && (!a.available() || a.capacity() <= 0)) throw new IllegalStateException("worker has no available capacity");
        Assignment assignment = new Assignment(assignmentId, objectiveRef, workerId, participationId, authorityRef,
                authorizationRef, description, AssignmentStatus.ACTIVE, Instant.now(), completionPolicy);
        Assignment prior = assignments.putIfAbsent(assignmentId, assignment);
        if (prior != null) throw new IllegalStateException("assignment already exists");
        persist(); return assignment;
    }

    public synchronized Assignment transitionAssignment(String assignmentId, AssignmentStatus status) {
        Assignment old = requireAssignment(assignmentId); Objects.requireNonNull(status);
        if (old.status() == AssignmentStatus.COMPLETED || old.status() == AssignmentStatus.CANCELLED)
            throw new IllegalStateException("terminal assignment cannot transition");
        if (status == AssignmentStatus.COMPLETED && !completionGate.satisfiesCompletion(old, old.completionPolicy())) {
            // Authoritative chokepoint: every known path to COMPLETED (normal execution success,
            // GovernedAutonomousExecutionCapability.reconcileTerminalExecutionCapacity(), and the
            // WorkforceCoreController HTTP status-transition endpoint) calls this method, and nothing
            // else in this codebase constructs a COMPLETED Assignment -- so this check cannot be
            // bypassed by any caller, privileged or not, without changing this method itself.
            throw new IllegalStateException("completion-evidence-required:" + old.completionPolicy() + ":" + assignmentId);
        }
        Assignment next = new Assignment(old.assignmentId(), old.objectiveRef(), old.workerId(), old.participationId(),
                old.authorityRef(), old.authorizationRef(), old.description(), status, old.createdAt(), old.completionPolicy());
        assignments.put(assignmentId, next);
        if (status == AssignmentStatus.COMPLETED || status == AssignmentStatus.CANCELLED) {
            capacityReservations.values().stream()
                    .filter(r -> r.assignmentId().equals(assignmentId) && r.status() == ReservationStatus.ACTIVE)
                    .map(CapacityReservation::reservationId).toList()
                    .forEach(id -> releaseCapacity(id, "terminal-assignment:" + status, clock.instant()));
        }
        persist(); return next;
    }

    public synchronized double remainingCapacity(String workerId) {
        Availability a = availability.get(workerId);
        if (a == null || !a.available()) return 0.0;
        return Math.max(0.0, a.capacity() - reservedCapacity(workerId));
    }

    public synchronized List<Worker> eligibleWorkers(String capabilityRef, double minimumLevel,
                                                      double requiredCapacity, Instant at) {
        require(capabilityRef, "capabilityRef"); Objects.requireNonNull(at, "at");
        if (!Double.isFinite(minimumLevel) || minimumLevel < 0) throw new IllegalArgumentException("minimumLevel invalid");
        if (!Double.isFinite(requiredCapacity) || requiredCapacity <= 0) throw new IllegalArgumentException("requiredCapacity invalid");
        return workers.values().stream()
                .filter(w -> w.status() == WorkerStatus.ACTIVE)
                .filter(w -> capabilities.getOrDefault(w.workerId(), Map.of()).get(capabilityRef) != null)
                .filter(w -> capabilities.get(w.workerId()).get(capabilityRef).level() >= minimumLevel)
                .filter(w -> participations.values().stream().anyMatch(p -> p.workerId().equals(w.workerId())
                        && p.status() == ParticipationStatus.ACTIVE))
                .filter(w -> remainingCapacity(w.workerId()) + 1e-9 >= requiredCapacity)
                .sorted(Comparator.comparingDouble((Worker w) -> remainingCapacity(w.workerId())).reversed()
                        .thenComparing(Worker::workerId))
                .toList();
    }

    public Worker worker(String id) { return Optional.ofNullable(workers.get(id)).orElseThrow(() -> new NoSuchElementException("worker not found")); }
    public List<Participant> allParticipants() { return participants.values().stream().sorted(Comparator.comparing(Participant::recognizedAt)).toList(); }
    public List<Worker> allWorkers() { return workers.values().stream().sorted(Comparator.comparing(Worker::admittedAt)).toList(); }
    public List<Participation> allParticipations() { return participations.values().stream().sorted(Comparator.comparing(Participation::startedAt)).toList(); }
    public List<Assignment> allAssignments() { return assignments.values().stream().sorted(Comparator.comparing(Assignment::createdAt)).toList(); }
    public List<CapacityReservation> allCapacityReservations() { return capacityReservations.values().stream().sorted(Comparator.comparing(CapacityReservation::createdAt)).toList(); }
    public List<Participation> participations(String workerId) { return participations.values().stream().filter(p -> p.workerId().equals(workerId)).toList(); }
    public List<Assignment> assignments(String workerId) { return assignments.values().stream().filter(a -> a.workerId().equals(workerId)).toList(); }
    public List<Capability> capabilities(String workerId) { return List.copyOf(capabilities.getOrDefault(workerId, Map.of()).values()); }
    public List<Qualification> qualifications(String workerId) { return List.copyOf(qualifications.getOrDefault(workerId, Map.of()).values()); }
    public Optional<Availability> availability(String workerId) { return Optional.ofNullable(availability.get(workerId)); }

    private double reservedCapacity(String workerId) {
        return capacityReservations.values().stream()
                .filter(r -> r.workerId().equals(workerId) && r.status() == ReservationStatus.ACTIVE)
                .mapToDouble(CapacityReservation::capacity).sum();
    }
    private Worker activeWorker(String id) { Worker w = worker(id); if (w.status() != WorkerStatus.ACTIVE) throw new IllegalStateException("worker not active"); return w; }
    private Participation requireParticipation(String id) { return Optional.ofNullable(participations.get(id)).orElseThrow(() -> new NoSuchElementException("participation not found")); }
    private Assignment requireAssignment(String id) { return Optional.ofNullable(assignments.get(id)).orElseThrow(() -> new NoSuchElementException("assignment not found")); }
    private CapacityReservation requireReservation(String id) { return Optional.ofNullable(capacityReservations.get(id)).orElseThrow(() -> new NoSuchElementException("capacity reservation not found")); }
    private void persist() { stateStore.save(new WorkforceCoreStateStore.Snapshot(participants, workers, participations, capabilities, qualifications, availability, assignments, capacityReservations)); }
    private static void require(String value, String name) { if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required"); }
}
