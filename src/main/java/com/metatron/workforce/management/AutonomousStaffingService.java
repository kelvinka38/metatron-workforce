package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.operating.WorkerConstitutionService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Workforce Management staffing orchestrator. It coordinates approved formation but writes all
 * institutional identity, participation, capability and capacity state through Workforce Core.
 * A formed Worker is not usable until its approved runtime/tool profile is durably bound.
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
    private final WorkerRuntimeProfileBindingService runtimeProfiles;
    private final WorkerConstitutionService constitution;

    public AutonomousStaffingService(WorkforceCoreService core, List<AutonomousStaffingPolicy> policies) {
        this(core, policies, WorkerRuntimeProfileBindingService.inMemory(), WorkerConstitutionService.inMemory());
    }

    public AutonomousStaffingService(WorkforceCoreService core,
                                     List<AutonomousStaffingPolicy> policies,
                                     WorkerRuntimeProfileBindingService runtimeProfiles) {
        this(core, policies, runtimeProfiles, WorkerConstitutionService.inMemory());
    }

    public AutonomousStaffingService(WorkforceCoreService core,
                                     List<AutonomousStaffingPolicy> policies,
                                     WorkerRuntimeProfileBindingService runtimeProfiles,
                                     WorkerConstitutionService constitution) {
        this.core = Objects.requireNonNull(core);
        this.runtimeProfiles = Objects.requireNonNull(runtimeProfiles, "runtimeProfiles");
        this.constitution = Objects.requireNonNull(constitution, "constitution");
        this.policies = List.copyOf(policies).stream().collect(Collectors.toUnmodifiableMap(
                AutonomousStaffingPolicy::capabilityRef,
                Function.identity(),
                (a, b) -> { throw new IllegalStateException("duplicate staffing policy: " + a.capabilityRef()); }));
    }

    /**
     * Resolve a real allocation gap. This method is synchronized because formation is an
     * institutional transition, not parallel runtime scaling.
     */
    public synchronized StaffingOutcome ensureStaffed(AutonomousExecutionCapability capability, Instant at) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(at, "at");

        var eligible = core.eligibleWorkers(capability.capabilityRef(), capability.minimumCapabilityLevel(),
                        capability.requiredCapacity(), at).stream()
                .filter(w -> capability.supportsWorker(w.workerId()))
                .toList();
        if (!eligible.isEmpty()) {
            String workerId = eligible.getFirst().workerId();
            AutonomousStaffingPolicy policy = policies.get(capability.capabilityRef());
            List<String> evidence;
            if (policy != null) {
                var spec = policy.formationSpec();
                WorkerRuntimeProfileBindingService.Binding binding = runtimeProfiles.bind(
                        workerId, spec.runtimeProfileRef(), capability.capabilityRef(), at);
                WorkerConstitutionService.WorkerPositionBinding constitutionBinding =
                        constitution.ensureConstitution(policy, at);
                evidence = List.of(
                        "staffing:reused-worker=" + workerId,
                        "position-contract-bound:" + constitutionBinding.contractId(),
                        "runtime-profile-bound:" + binding.profile().profileRef(),
                        "runtime-actions=" + binding.profile().actionRefs().stream().sorted().toList());
            } else {
                evidence = List.of("staffing:reused-worker=" + workerId);
            }
            return new StaffingOutcome(GapReason.STAFFED, workerId, evidence);
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
        for (AutonomousStaffingPolicy.CapabilityGrant grant : policy.additionalCapabilities()) {
            core.attestCapability(spec.workerId(), grant.capabilityRef(), grant.level(), grant.evidenceRef());
        }
        core.attestQualification(spec.workerId(), spec.qualificationRef(), spec.qualificationEvidenceRef(), null);
        WorkerConstitutionService.WorkerPositionBinding constitutionBinding =
                constitution.ensureConstitution(policy, at);
        core.setAvailability(spec.workerId(), true, spec.capacity());
        WorkerRuntimeProfileBindingService.Binding runtimeBinding = runtimeProfiles.bind(
                spec.workerId(), spec.runtimeProfileRef(), capability.capabilityRef(), at);

        var nowEligible = core.eligibleWorkers(capability.capabilityRef(), capability.minimumCapabilityLevel(),
                        capability.requiredCapacity(), at).stream()
                .filter(w -> capability.supportsWorker(w.workerId()))
                .sorted(Comparator.comparing(WorkforceCoreService.Worker::workerId))
                .toList();
        if (nowEligible.isEmpty()) {
            throw new StaffingGapException(GapReason.POLICY_IDENTITY_CONFLICT, capability.capabilityRef());
        }

        return new StaffingOutcome(GapReason.STAFFED, nowEligible.getFirst().workerId(), List.of(
                "staffing:policy=" + capability.capabilityRef(),
                "participant:" + spec.participantId(),
                "worker:" + spec.workerId(),
                "participation:" + spec.participationId(),
                "capability-evidence:" + spec.capabilityEvidenceRef(),
                "additional-capabilities=" + policy.additionalCapabilities().stream()
                        .map(AutonomousStaffingPolicy.CapabilityGrant::capabilityRef).sorted().toList(),
                "qualification-evidence:" + spec.qualificationEvidenceRef(),
                "authority-envelope:" + spec.authorityEnvelopeRef(),
                "position-contract-bound:" + constitutionBinding.contractId(),
                "runtime-profile-bound:" + runtimeBinding.profile().profileRef(),
                "runtime-actions=" + runtimeBinding.profile().actionRefs().stream().sorted().toList(),
                "cost-limit:" + spec.costLimitRef(),
                "lifecycle:" + spec.lifecycleRef()));
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
