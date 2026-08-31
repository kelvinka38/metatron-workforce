package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Workforce Management staffing orchestrator. It coordinates approved formation but writes all
 * institutional identity, participation, capability, qualification and capacity state through Workforce Core.
 */
public final class AutonomousStaffingService {
    public enum GapReason {
        POLICY_MISSING,
        FORMATION_PROHIBITED,
        AUTHORITY_ENVELOPE_MISMATCH,
        POLICY_IDENTITY_CONFLICT,
        STAFFED
    }

    public record StaffingOutcome(GapReason reason, String workerId, List<String> evidenceReferences) {
        public StaffingOutcome {
            Objects.requireNonNull(reason);
            workerId = workerId == null ? "" : workerId;
            evidenceReferences = List.copyOf(evidenceReferences == null ? List.of() : evidenceReferences);
        }
        public boolean staffed() { return reason == GapReason.STAFFED; }
    }

    public static final class StaffingGapException extends IllegalStateException {
        private final GapReason reason;
        public StaffingGapException(GapReason reason, String capabilityRef) {
            super("staffing-gap:" + reason.name().toLowerCase() + ":" + capabilityRef);
            this.reason = reason;
        }
        public GapReason reason() { return reason; }
    }

    private final WorkforceCoreService core;
    private final Map<String, AutonomousStaffingPolicy> policies;

    public AutonomousStaffingService(WorkforceCoreService core, List<AutonomousStaffingPolicy> policies) {
        this.core = Objects.requireNonNull(core);
        this.policies = List.copyOf(policies).stream().collect(Collectors.toUnmodifiableMap(
                AutonomousStaffingPolicy::capabilityRef,
                Function.identity(),
                (a, b) -> { throw new IllegalStateException("duplicate staffing policy: " + a.capabilityRef()); }));
    }

    /**
     * Authoritative eligibility projection used by both scheduling and execution allocation.
     * When a capability has an approved formation policy, its qualification is mandatory and must
     * still be valid at the decision instant. Capability, participation, qualification and finite
     * remaining capacity therefore all affect real Worker eligibility.
     */
    public synchronized List<WorkforceCoreService.Worker> eligibleWorkers(
            AutonomousExecutionCapability capability, Instant at) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(at, "at");
        AutonomousStaffingPolicy policy = policies.get(capability.capabilityRef());
        String requiredQualification = policy == null ? "" : policy.formationSpec().qualificationRef();
        return core.eligibleWorkers(capability.capabilityRef(), capability.minimumCapabilityLevel(),
                        capability.requiredCapacity(), at).stream()
                .filter(w -> capability.supportsWorker(w.workerId()))
                .filter(w -> requiredQualification.isBlank() || hasValidQualification(
                        w.workerId(), requiredQualification, at))
                .sorted(Comparator.comparingDouble((WorkforceCoreService.Worker w) -> core.remainingCapacity(w.workerId()))
                        .reversed().thenComparing(WorkforceCoreService.Worker::workerId))
                .toList();
    }

    /** True when institutional identity/capability/participation/qualification exist, ignoring current capacity. */
    public synchronized boolean hasQualifiedParticipant(AutonomousExecutionCapability capability, Instant at) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(at, "at");
        AutonomousStaffingPolicy policy = policies.get(capability.capabilityRef());
        String requiredQualification = policy == null ? "" : policy.formationSpec().qualificationRef();
        return core.allWorkers().stream()
                .filter(w -> w.status() == WorkforceCoreService.WorkerStatus.ACTIVE)
                .filter(w -> capability.supportsWorker(w.workerId()))
                .filter(w -> core.capabilities(w.workerId()).stream().anyMatch(c ->
                        c.capabilityRef().equals(capability.capabilityRef())
                                && c.level() >= capability.minimumCapabilityLevel()))
                .filter(w -> core.participations(w.workerId()).stream()
                        .anyMatch(p -> p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE))
                .anyMatch(w -> requiredQualification.isBlank()
                        || hasValidQualification(w.workerId(), requiredQualification, at));
    }

    /**
     * Resolve a real allocation gap. This method is synchronized because formation is an
     * institutional transition, not parallel runtime scaling.
     */
    public synchronized StaffingOutcome ensureStaffed(AutonomousExecutionCapability capability, Instant at) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(at, "at");

        var eligible = eligibleWorkers(capability, at);
        if (!eligible.isEmpty()) {
            return new StaffingOutcome(GapReason.STAFFED, eligible.getFirst().workerId(),
                    List.of("staffing:reused-worker=" + eligible.getFirst().workerId()));
        }

        AutonomousStaffingPolicy policy = policies.get(capability.capabilityRef());
        if (policy == null) throw new StaffingGapException(GapReason.POLICY_MISSING, capability.capabilityRef());
        AutonomousStaffingPolicy.FormationSpec spec = policy.formationSpec();
        if (!spec.formationPermitted()) throw new StaffingGapException(GapReason.FORMATION_PROHIBITED, capability.capabilityRef());
        if (!spec.authorityEnvelopeRef().equals(capability.authorityReference())) {
            throw new StaffingGapException(GapReason.AUTHORITY_ENVELOPE_MISMATCH, capability.capabilityRef());
        }

        validateExistingIdentity(spec, capability.capabilityRef());

        core.recognizeParticipant(spec.participantId(), spec.participantType(), spec.participantProvenanceRef());
        try {
            core.worker(spec.workerId());
        } catch (java.util.NoSuchElementException absent) {
            core.admitWorker(spec.workerId(), spec.participantId());
        }

        var participation = core.allParticipations().stream()
                .filter(p -> p.participationId().equals(spec.participationId()))
                .findFirst();
        if (participation.isEmpty()) {
            core.participate(spec.participationId(), spec.workerId(), spec.organizationRef(), spec.positionRef(), spec.roleRef());
        } else if (!participation.get().workerId().equals(spec.workerId())
                || !participation.get().organizationRef().equals(spec.organizationRef())) {
            throw new StaffingGapException(GapReason.POLICY_IDENTITY_CONFLICT, capability.capabilityRef());
        }

        core.attestCapability(spec.workerId(), capability.capabilityRef(), spec.capabilityLevel(), spec.capabilityEvidenceRef());
        core.attestQualification(spec.workerId(), spec.qualificationRef(), spec.qualificationEvidenceRef(), null);
        core.setAvailability(spec.workerId(), true, spec.capacity());

        var nowEligible = eligibleWorkers(capability, at);
        if (nowEligible.isEmpty()) {
            throw new StaffingGapException(GapReason.POLICY_IDENTITY_CONFLICT, capability.capabilityRef());
        }

        return new StaffingOutcome(GapReason.STAFFED, nowEligible.getFirst().workerId(), List.of(
                "staffing:policy=" + capability.capabilityRef(),
                "participant:" + spec.participantId(),
                "worker:" + spec.workerId(),
                "participation:" + spec.participationId(),
                "capability-evidence:" + spec.capabilityEvidenceRef(),
                "qualification-evidence:" + spec.qualificationEvidenceRef(),
                "authority-envelope:" + spec.authorityEnvelopeRef(),
                "runtime-profile:" + spec.runtimeProfileRef(),
                "cost-limit:" + spec.costLimitRef(),
                "lifecycle:" + spec.lifecycleRef()));
    }

    private boolean hasValidQualification(String workerId, String qualificationRef, Instant at) {
        return core.qualifications(workerId).stream().anyMatch(q ->
                q.qualificationRef().equals(qualificationRef)
                        && (q.validUntil() == null || !at.isAfter(q.validUntil())));
    }

    private void validateExistingIdentity(AutonomousStaffingPolicy.FormationSpec spec, String capabilityRef) {
        core.allParticipants().stream().filter(p -> p.participantId().equals(spec.participantId())).findFirst().ifPresent(p -> {
            if (p.type() != spec.participantType() || !p.provenanceRef().equals(spec.participantProvenanceRef())) {
                throw new StaffingGapException(GapReason.POLICY_IDENTITY_CONFLICT, capabilityRef);
            }
        });
        core.allWorkers().stream().filter(w -> w.workerId().equals(spec.workerId())).findFirst().ifPresent(w -> {
            if (!w.participantId().equals(spec.participantId()) || w.status() != WorkforceCoreService.WorkerStatus.ACTIVE) {
                throw new StaffingGapException(GapReason.POLICY_IDENTITY_CONFLICT, capabilityRef);
            }
        });
    }
}
