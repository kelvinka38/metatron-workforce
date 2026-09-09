package com.metatron.workforce.workplace;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.memory.PersistentWorkerConversationMemoryStore;
import com.metatron.workforce.management.AutonomousStaffingPolicy;
import com.metatron.workforce.runtime.RuntimeInstance;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only Worker operating profile projection.
 *
 * This service does not invent a monolithic Worker object. It composes the semantic relationships
 * required by the canonical Workforce/Gateway SOT from their current authoritative runtime sources.
 */
@Service
public final class WorkerOperatingProfileService {
    private final WorkforceCoreService core;
    private final WorkerRuntimeProfileBindingService runtimeProfiles;
    private final RuntimeRegistry runtimes;
    private final WorkplaceDashboardService dashboard;
    private final List<AutonomousStaffingPolicy> staffingPolicies;
    private final InstitutionalRoleGrounding grounding;
    private final PersistentWorkerConversationMemoryStore memory;

    public WorkerOperatingProfileService(
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeRegistry runtimes,
            WorkplaceDashboardService dashboard,
            List<AutonomousStaffingPolicy> staffingPolicies,
            InstitutionalRoleGrounding grounding,
            PersistentWorkerConversationMemoryStore memory) {
        this.core = Objects.requireNonNull(core, "core");
        this.runtimeProfiles = Objects.requireNonNull(runtimeProfiles, "runtimeProfiles");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.dashboard = Objects.requireNonNull(dashboard, "dashboard");
        this.staffingPolicies = List.copyOf(Objects.requireNonNull(staffingPolicies, "staffingPolicies"));
        this.grounding = Objects.requireNonNull(grounding, "grounding");
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    public OperatingProfile profile(String workerId) {
        WorkforceCoreService.Worker worker = core.worker(workerId);
        WorkforceCoreService.Participant participant = core.allParticipants().stream()
                .filter(item -> item.participantId().equals(worker.participantId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("worker participant missing:" + workerId));

        List<WorkforceCoreService.Participation> activeParticipations = core.participations(workerId).stream()
                .filter(item -> item.status() == WorkforceCoreService.ParticipationStatus.ACTIVE)
                .sorted(Comparator.comparing(WorkforceCoreService.Participation::startedAt))
                .toList();
        WorkforceCoreService.Participation primary = activeParticipations.stream().findFirst().orElse(null);

        WorkerRuntimeProfileBindingService.Binding binding = runtimeProfiles.find(workerId).orElse(null);
        RuntimeInstance runtime = runtimes.runningForWorker(workerId).orElse(null);

        Optional<AutonomousStaffingPolicy> formationPolicy = staffingPolicies.stream()
                .filter(policy -> workerId.equals(policy.formationSpec().workerId()))
                .findFirst();
        FormationProfile formation = formationPolicy.map(policy -> formationProfile(policy, policy.formationSpec()))
                .orElse(FormationProfile.unavailable());

        String roleRef = primary == null ? "" : primary.roleRef();
        String positionRef = primary == null ? "" : primary.positionRef();
        InstitutionalRoleGrounding.Grounding canonical = grounding.resolve(
                roleRef,
                positionRef,
                roleRef,
                "institutional mandate continuous ownership mission responsibilities reporting relationship capabilities "
                        + "authority resource scope escalation success measures staffing budget cost capacity reliability "
                        + "performance management accountability operating model 24/7");
        CanonicalMandate mandate = new CanonicalMandate(
                canonical.available(),
                canonical.domain(),
                canonical.available() ? canonical.context() : "",
                canonical.evidenceReferences(),
                canonical.reason());

        List<PersistentWorkerConversationMemoryStore.Turn> memoryTurns =
                memory.history("human-primary", workerId);
        List<String> channels = memoryTurns.stream()
                .map(PersistentWorkerConversationMemoryStore.Turn::channel)
                .filter(value -> value != null && !value.isBlank())
                .distinct().sorted().toList();
        MemoryProfile memoryProfile = new MemoryProfile(
                memoryTurns.size(),
                channels,
                memoryTurns.isEmpty() ? null : memoryTurns.getLast().at(),
                "human-primary + worker_id",
                500);

        RuntimeProfile runtimeProfile = binding == null
                ? RuntimeProfile.unavailable(runtime == null ? "NOT_RUNNING" : runtime.state().name())
                : new RuntimeProfile(
                        binding.profile().profileRef(),
                        binding.capabilityRef(),
                        binding.boundAt(),
                        binding.profile().actionRefs().stream().sorted().toList(),
                        binding.profile().allowedExecutables().stream().sorted().toList(),
                        binding.profile().writableWorkspace(),
                        binding.profile().maxProcessSeconds(),
                        binding.profile().maxOutputBytes(),
                        runtime == null ? "" : runtime.runtimeId(),
                        runtime == null ? "NOT_RUNNING" : runtime.state().name(),
                        runtime == null ? null : runtime.createdAt());

        WorkplaceDashboardService.Dashboard state = dashboard.dashboard();
        List<WorkplaceDashboardService.ActionPulse> actionRecords = state.objectivePulse().stream()
                .flatMap(objective -> objective.actionRecords().stream())
                .filter(action -> workerId.equals(action.workerId()))
                .sorted(Comparator.comparing(WorkplaceDashboardService.ActionPulse::recordedAt))
                .toList();
        long successfulActions = actionRecords.stream().filter(WorkplaceDashboardService.ActionPulse::success).count();
        long failedActions = actionRecords.size() - successfulActions;
        long ownedObjectives = state.objectivePulse().stream()
                .filter(objective -> workerId.equals(objective.ownerWorkerId())).count();
        long completedObjectives = state.objectivePulse().stream()
                .filter(objective -> workerId.equals(objective.ownerWorkerId()))
                .filter(objective -> "COMPLETED_WITH_EVIDENCE".equals(objective.executionState())).count();

        List<WorkforceCoreService.Assignment> assignments = core.assignments(workerId);
        List<AuthorityUse> authorityUses = assignments.stream()
                .map(assignment -> new AuthorityUse(
                        assignment.assignmentId(),
                        assignment.objectiveRef(),
                        assignment.authorityRef(),
                        assignment.authorizationRef(),
                        assignment.status().name()))
                .toList();

        PerformanceProfile performance = new PerformanceProfile(
                ownedObjectives,
                completedObjectives,
                assignments.size(),
                actionRecords.size(),
                successfulActions,
                failedActions,
                actionRecords.isEmpty() ? null : actionRecords.getLast().recordedAt(),
                false);

        LinkedHashSet<String> gaps = new LinkedHashSet<>();
        gaps.add("position.mission: CANONICAL_SOURCE_ONLY — not materialized as a first-class live Position contract");
        gaps.add("position.reporting_relationship: NOT_MODELED in live Workforce Core projection");
        gaps.add("position.resource_scope: PARTIAL — cost/capacity exist, full resource envelope is not materialized");
        gaps.add("position.escalation_route: NOT_MODELED in live Worker profile");
        gaps.add("position.success_measures: CANONICAL_SOURCE_ONLY — no first-class KPI contract bound to this Position");
        gaps.add("worker.schedule/work_periods: NOT_MODELED in live Worker profile");
        gaps.add("worker.performance_evaluation: NOT_MODELED — dashboard counts are operational evidence, not formal evaluation");
        gaps.add("worker.experience/learning: PARTIAL — conversation memory exists; institutional Experience/Learning records are not projected here");

        return new OperatingProfile(
                worker.workerId(),
                worker.status().name(),
                worker.admittedAt(),
                participant.participantId(),
                participant.type().name(),
                participant.provenanceRef(),
                primary == null ? "" : primary.organizationRef(),
                positionRef,
                roleRef,
                formation,
                mandate,
                runtimeProfile,
                memoryProfile,
                performance,
                core.capabilities(workerId),
                core.qualifications(workerId),
                authorityUses,
                CanonicalWorkerConversationService.liveConversationInstructions(),
                List.copyOf(gaps));
    }

    private static FormationProfile formationProfile(
            AutonomousStaffingPolicy policy,
            AutonomousStaffingPolicy.FormationSpec spec) {
        return new FormationProfile(
                true,
                policy.capabilityRef(),
                spec.formationPermitted(),
                spec.organizationRef(),
                spec.positionRef(),
                spec.roleRef(),
                spec.qualificationRef(),
                spec.qualificationEvidenceRef(),
                spec.authorityEnvelopeRef(),
                spec.capacity(),
                spec.runtimeProfileRef(),
                spec.costLimitRef(),
                spec.lifecycleRef(),
                policy.additionalCapabilities().stream()
                        .map(grant -> grant.capabilityRef() + "@" + grant.level())
                        .sorted().toList());
    }

    public record FormationProfile(
            boolean available,
            String policyCapabilityRef,
            boolean formationPermitted,
            String organizationRef,
            String positionRef,
            String roleRef,
            String qualificationRef,
            String qualificationEvidenceRef,
            String authorityEnvelopeRef,
            double configuredCapacity,
            String runtimeProfileRef,
            String costLimitRef,
            String lifecycleRef,
            List<String> additionalCapabilities) {
        static FormationProfile unavailable() {
            return new FormationProfile(false, "", false, "", "", "", "", "", "", 0, "", "", "", List.of());
        }
    }

    public record CanonicalMandate(
            boolean available,
            String domain,
            String content,
            List<String> sourceReferences,
            String unavailableReason) {}

    public record RuntimeProfile(
            String profileRef,
            String bindingCapabilityRef,
            Instant boundAt,
            List<String> actionRefs,
            List<String> allowedExecutables,
            boolean writableWorkspace,
            int maxProcessSeconds,
            int maxOutputBytes,
            String runtimeId,
            String runtimeState,
            Instant runtimeCreatedAt) {
        static RuntimeProfile unavailable(String runtimeState) {
            return new RuntimeProfile("", "", null, List.of(), List.of(), false, 0, 0, "", runtimeState, null);
        }
    }

    public record MemoryProfile(
            int turns,
            List<String> channels,
            Instant lastTurnAt,
            String identityKey,
            int maxStoredTurns) {}

    public record PerformanceProfile(
            long ownedObjectives,
            long completedObjectives,
            long assignments,
            long actionRecords,
            long successfulActions,
            long failedActions,
            Instant lastActionAt,
            boolean formalEvaluationAvailable) {}

    public record AuthorityUse(
            String assignmentId,
            String objectiveRef,
            String authorityRef,
            String authorizationRef,
            String assignmentStatus) {}

    public record OperatingProfile(
            String workerId,
            String status,
            Instant admittedAt,
            String participantId,
            String participantType,
            String participantProvenanceRef,
            String organizationRef,
            String positionRef,
            String roleRef,
            FormationProfile formation,
            CanonicalMandate canonicalMandate,
            RuntimeProfile runtime,
            MemoryProfile memory,
            PerformanceProfile performance,
            List<WorkforceCoreService.Capability> capabilities,
            List<WorkforceCoreService.Qualification> qualifications,
            List<AuthorityUse> authorityUses,
            String liveCognitionInstructions,
            List<String> modelGaps) {}
}
