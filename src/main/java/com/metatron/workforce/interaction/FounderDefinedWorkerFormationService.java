package com.metatron.workforce.interaction;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.management.AutonomousStaffingPolicy;
import com.metatron.workforce.operating.WorkerConstitutionService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeInstance;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Founder-authorized formation path for durable first-class Workers whose Position is defined at runtime.
 *
 * <p>This service does not create provider personas. It materializes canonical Workforce Core identity,
 * Participation, capability/qualification, durable Position constitution, runtime profile and a live
 * runtime. Role text never grants shell/Git/deploy authority; founder-defined Workers receive only the
 * bounded cognitive-work capability until additional governed capabilities are explicitly granted.</p>
 */
@Service
public final class FounderDefinedWorkerFormationService {
    public static final String COGNITIVE_CAPABILITY = "worker.cognitive.work";
    public static final String CONVERSATION_CAPABILITY = "worker.live.conversation";
    public static final String RUNTIME_PROFILE = "runtime-profile:founder-cognitive-worker:v1";
    public static final String AUTHORITY_REF = "authority:founder-defined-cognitive-work:v1";
    public static final String ORGANIZATION_REF = "metatron";

    private final WorkforceCoreService core;
    private final WorkerRuntimeProfileBindingService runtimeProfiles;
    private final WorkerConstitutionService constitution;
    private final RuntimeCapacityCoordinator runtimes;

    public FounderDefinedWorkerFormationService(
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            WorkerConstitutionService constitution,
            RuntimeCapacityCoordinator runtimes) {
        this.core = Objects.requireNonNull(core, "core");
        this.runtimeProfiles = Objects.requireNonNull(runtimeProfiles, "runtimeProfiles");
        this.constitution = Objects.requireNonNull(constitution, "constitution");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    }

    public synchronized FormationResult form(String role, String founderBrief, Instant at) {
        String humanRole = cleanRole(role);
        String slug = slug(humanRole);
        String workerId = "WORKER-" + slug.toUpperCase(Locale.ROOT);
        String participantId = "participant:founder-worker:" + slug;
        String participationId = "participation:founder-worker:" + slug + ":v1";
        String roleRef = "role:founder-defined:" + slug;
        String positionRef = "position:founder-defined:" + slug;
        String provenanceRef = "founder-defined-worker:" + slug + ":v1";
        String capabilityEvidence = "founder-formation:capability:" + workerId;
        String qualificationRef = "qualification:founder-defined-role:" + slug + ":v1";
        String qualificationEvidence = "founder-formation:qualification:" + workerId;
        Objects.requireNonNull(at, "at");

        WorkforceCoreService.Worker existing = core.allWorkers().stream()
                .filter(value -> value.workerId().equals(workerId))
                .findFirst().orElse(null);
        boolean created = existing == null;

        core.recognizeParticipant(participantId, WorkforceCoreService.ParticipantType.AI, provenanceRef);
        WorkforceCoreService.Worker worker;
        if (existing == null) {
            worker = core.admitWorker(workerId, participantId);
        } else {
            if (!existing.participantId().equals(participantId)) {
                throw new IllegalStateException("founder-worker-identity-conflict:" + workerId);
            }
            if (existing.status() == WorkforceCoreService.WorkerStatus.RETIRED) {
                throw new IllegalStateException("founder-worker-retired-cannot-reactivate:" + workerId);
            }
            worker = existing.status() == WorkforceCoreService.WorkerStatus.ACTIVE
                    ? existing : core.setWorkerStatus(workerId, WorkforceCoreService.WorkerStatus.ACTIVE);
        }

        WorkforceCoreService.Participation participation = core.allParticipations().stream()
                .filter(value -> value.participationId().equals(participationId))
                .findFirst().orElse(null);
        if (participation == null) {
            participation = core.participate(participationId, workerId, ORGANIZATION_REF, positionRef, roleRef);
        } else {
            if (!participation.workerId().equals(workerId)
                    || !participation.organizationRef().equals(ORGANIZATION_REF)
                    || !participation.positionRef().equals(positionRef)
                    || !participation.roleRef().equals(roleRef)) {
                throw new IllegalStateException("founder-worker-participation-conflict:" + workerId);
            }
            if (participation.status() != WorkforceCoreService.ParticipationStatus.ACTIVE) {
                participation = core.setParticipationStatus(participationId, WorkforceCoreService.ParticipationStatus.ACTIVE);
            }
        }

        core.attestCapability(workerId, COGNITIVE_CAPABILITY, 1.0, capabilityEvidence);
        core.attestCapability(workerId, CONVERSATION_CAPABILITY, 1.0, capabilityEvidence + ":conversation");
        core.attestQualification(workerId, qualificationRef, qualificationEvidence, null);
        core.setAvailability(workerId, true, 1.0);

        WorkerRuntimeProfileBindingService.Binding runtimeBinding = runtimeProfiles.bind(
                workerId, RUNTIME_PROFILE, COGNITIVE_CAPABILITY, at);

        WorkerConstitutionService.WorkerPositionBinding constitutionBinding;
        if (created || constitution.binding(workerId, participationId).isEmpty()) {
            DynamicFounderPolicy policy = new DynamicFounderPolicy(
                    workerId, participantId, participationId, positionRef, roleRef,
                    capabilityEvidence, qualificationRef, qualificationEvidence,
                    humanRole, founderBrief == null ? "" : founderBrief.trim());
            constitutionBinding = constitution.ensureConstitution(policy, at);
        } else {
            constitutionBinding = constitution.requireBinding(workerId, participationId);
        }

        RuntimeInstance runtime = runtimes.ensureRunning(workerId);
        return new FormationResult(created, worker, participation, runtimeBinding, constitutionBinding, runtime, humanRole);
    }

    public synchronized FormationResult activate(String workerId, Instant at) {
        Objects.requireNonNull(at, "at");
        WorkforceCoreService.Worker worker = core.worker(require(workerId, "workerId"));
        if (worker.status() == WorkforceCoreService.WorkerStatus.RETIRED) {
            throw new IllegalStateException("retired-worker-cannot-reactivate:" + worker.workerId());
        }
        if (worker.status() != WorkforceCoreService.WorkerStatus.ACTIVE) {
            worker = core.setWorkerStatus(worker.workerId(), WorkforceCoreService.WorkerStatus.ACTIVE);
        }
        WorkforceCoreService.Participation participation = primaryParticipation(worker.workerId());
        if (participation.status() != WorkforceCoreService.ParticipationStatus.ACTIVE) {
            participation = core.setParticipationStatus(participation.participationId(), WorkforceCoreService.ParticipationStatus.ACTIVE);
        }
        core.setAvailability(worker.workerId(), true,
                Math.max(1.0, core.availability(worker.workerId()).map(WorkforceCoreService.Availability::capacity).orElse(1.0)));
        WorkerRuntimeProfileBindingService.Binding binding = runtimeProfiles.requireBinding(worker.workerId());
        WorkerConstitutionService.WorkerPositionBinding constitutionBinding =
                constitution.requireBinding(worker.workerId(), participation.participationId());
        RuntimeInstance runtime = runtimes.ensureRunning(worker.workerId());
        return new FormationResult(false, worker, participation, binding, constitutionBinding, runtime,
                humanize(participation.roleRef()));
    }

    public FormationResult inspect(String workerId) {
        WorkforceCoreService.Worker worker = core.worker(require(workerId, "workerId"));
        WorkforceCoreService.Participation participation = primaryParticipation(worker.workerId());
        WorkerRuntimeProfileBindingService.Binding binding = runtimeProfiles.requireBinding(worker.workerId());
        WorkerConstitutionService.WorkerPositionBinding constitutionBinding =
                constitution.requireBinding(worker.workerId(), participation.participationId());
        RuntimeInstance runtime = runtimes.ensureRunning(worker.workerId());
        return new FormationResult(false, worker, participation, binding, constitutionBinding, runtime,
                humanize(participation.roleRef()));
    }

    public String resolveWorkerId(String query) {
        String value = require(query, "worker query").trim();
        for (WorkforceCoreService.Worker worker : core.allWorkers()) {
            if (worker.workerId().equalsIgnoreCase(value)) return worker.workerId();
        }
        String wanted = normalize(value);
        List<String> matches = core.allWorkers().stream()
                .filter(worker -> worker.status() != WorkforceCoreService.WorkerStatus.RETIRED)
                .filter(worker -> core.participations(worker.workerId()).stream().anyMatch(p ->
                        normalize(p.roleRef()).contains(wanted)
                                || normalize(p.positionRef()).contains(wanted)
                                || wanted.contains(normalize(p.roleRef()))
                                || wanted.contains(normalize(p.positionRef()))))
                .map(WorkforceCoreService.Worker::workerId)
                .distinct().sorted().toList();
        if (matches.size() == 1) return matches.getFirst();
        if (matches.isEmpty()) throw new NoSuchElementException("worker not found: " + value);
        throw new IllegalStateException("worker query ambiguous: " + value + " -> " + matches);
    }

    private WorkforceCoreService.Participation primaryParticipation(String workerId) {
        List<WorkforceCoreService.Participation> all = core.participations(workerId).stream()
                .sorted(Comparator.comparing(WorkforceCoreService.Participation::startedAt))
                .toList();
        if (all.isEmpty()) throw new IllegalStateException("worker-has-no-participation:" + workerId);
        return all.stream().filter(p -> p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE)
                .findFirst().orElse(all.getFirst());
    }

    private static String cleanRole(String role) {
        String value = require(role, "role").trim().replaceAll("\\s+", " ");
        if (value.length() > 80) throw new IllegalArgumentException("role too long");
        return value;
    }

    static String slug(String value) {
        String result = WorkerLifecycleControlService.folded(value)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (result.isBlank()) throw new IllegalArgumentException("role cannot produce worker identity");
        return result.length() <= 48 ? result : result.substring(0, 48).replaceAll("-+$", "");
    }

    private static String humanize(String roleRef) {
        String value = roleRef == null ? "" : roleRef;
        int split = value.lastIndexOf(':');
        if (split >= 0 && split + 1 < value.length()) value = value.substring(split + 1);
        return value.replace('-', ' ');
    }

    private static String normalize(String value) {
        return WorkerLifecycleControlService.folded(value);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value;
    }

    public record FormationResult(
            boolean created,
            WorkforceCoreService.Worker worker,
            WorkforceCoreService.Participation participation,
            WorkerRuntimeProfileBindingService.Binding runtimeProfile,
            WorkerConstitutionService.WorkerPositionBinding constitutionBinding,
            RuntimeInstance runtime,
            String humanRole) {
        public FormationResult {
            Objects.requireNonNull(worker);
            Objects.requireNonNull(participation);
            Objects.requireNonNull(runtimeProfile);
            Objects.requireNonNull(constitutionBinding);
            Objects.requireNonNull(runtime);
            humanRole = humanRole == null ? "" : humanRole;
        }
    }

    private record DynamicFounderPolicy(
            String workerId,
            String participantId,
            String participationId,
            String positionRef,
            String roleRef,
            String capabilityEvidence,
            String qualificationRef,
            String qualificationEvidence,
            String humanRole,
            String founderBrief) implements AutonomousStaffingPolicy {

        @Override public String capabilityRef() { return COGNITIVE_CAPABILITY; }

        @Override
        public FormationSpec formationSpec() {
            return new FormationSpec(
                    true,
                    participantId,
                    WorkforceCoreService.ParticipantType.AI,
                    "founder-defined-worker:" + slug(humanRole) + ":v1",
                    workerId,
                    ORGANIZATION_REF,
                    participationId,
                    positionRef,
                    roleRef,
                    1.0,
                    capabilityEvidence,
                    qualificationRef,
                    qualificationEvidence,
                    AUTHORITY_REF,
                    1.0,
                    RUNTIME_PROFILE,
                    "cost-limit:founder-cognitive-worker:v1",
                    "lifecycle:founder-defined-worker:v1");
        }

        @Override
        public List<CapabilityGrant> additionalCapabilities() {
            return List.of(new CapabilityGrant(
                    CONVERSATION_CAPABILITY, 1.0, capabilityEvidence + ":conversation"));
        }

        @Override
        public PositionContractSpec positionContractSpec() {
            String brief = founderBrief == null || founderBrief.isBlank()
                    ? "Founder-defined role: " + humanRole
                    : founderBrief;
            return new PositionContractSpec(
                    "Operate as the Metatron " + humanRole + " Worker. Founder brief: " + brief,
                    List.of(
                            "Produce original, role-appropriate cognitive work for assigned Human/Objective requests.",
                            "Maintain continuity through canonical Worker memory and the durable Position constitution.",
                            "Distinguish ideas, recommendations and produced work from external execution or effects.",
                            "Request additional governed capabilities when work requires tools or effects outside cognitive scope."),
                    List.of(new ReportingLineSpec("REPORTS_TO", "human-primary",
                            "Founder-defined mission, priorities and acceptance of cognitive work")),
                    List.of(COGNITIVE_CAPABILITY, CONVERSATION_CAPABILITY),
                    List.of(AUTHORITY_REF),
                    List.of(
                            new ResourceScopeSpec("runtime-profile", RUNTIME_PROFILE),
                            new ResourceScopeSpec("capacity", "max:1.0"),
                            new ResourceScopeSpec("tool-effects", "none-unless-explicitly-granted")),
                    List.of(
                            new EscalationRouteSpec("CAPABILITY_REQUIRED", "human-primary",
                                    "Requested work requires a tool/effect capability not granted to this Worker"),
                            new EscalationRouteSpec("MATERIAL_UNCERTAINTY", "human-primary",
                                    "The requested creative/professional direction is materially ambiguous")),
                    List.of(
                            new SuccessMeasureSpec("useful-original-work", "Produces useful original work within the role brief",
                                    "Human-usable work product"),
                            new SuccessMeasureSpec("truthful-boundary", "Never claims unobserved external execution",
                                    "0 false execution claims")),
                    List.of(
                            "Reason, compose, analyze and recommend within the Founder-defined role.",
                            "Produce cognitive work products through canonical Worker conversation.",
                            "Escalate rather than self-grant execution authority."),
                    "ASSIGNMENT_OR_CONVERSATION_DRIVEN",
                    "UTC",
                    1);
        }
    }
}
