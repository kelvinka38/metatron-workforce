package com.metatron.workforce.operating;

import com.metatron.workforce.management.AutonomousStaffingPolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Workforce-owned durable Position constitution and Worker operating reality.
 *
 * Worker identity stays in Workforce Core. This service owns the first-class relationships that
 * make an occupied Position operationally meaningful: mission, responsibilities, reporting,
 * capability demand, authority/resource envelope, escalation, success measures, decision rights,
 * working/coverage policy, contextual performance, attributable experience and evidence-derived learning.
 */
public final class WorkerConstitutionService {
    private final WorkerConstitutionStateStore store;
    private final Map<String, PositionOperatingContract> contracts = new LinkedHashMap<>();
    private final Map<String, WorkerPositionBinding> bindings = new LinkedHashMap<>();
    private final Map<String, PerformanceEvaluation> performance = new LinkedHashMap<>();
    private final Map<String, ExperienceRecord> experiences = new LinkedHashMap<>();
    private final Map<String, LearningRecord> learning = new LinkedHashMap<>();

    public WorkerConstitutionService(WorkerConstitutionStateStore store) {
        this.store = Objects.requireNonNull(store, "store");
        WorkerConstitutionStateStore.Snapshot snapshot = store.load();
        snapshot.positionContracts().forEach(value -> contracts.put(value.positionRef(), value));
        snapshot.bindings().forEach(value -> bindings.put(bindingKey(value.workerId(), value.participationId()), value));
        snapshot.performanceEvaluations().forEach(value -> performance.put(value.workerId(), value));
        snapshot.experiences().forEach(value -> experiences.put(value.experienceId(), value));
        snapshot.learningRecords().forEach(value -> learning.put(value.learningId(), value));
    }

    public static WorkerConstitutionService inMemory() {
        return new WorkerConstitutionService(new WorkerConstitutionStateStore() {
            private Snapshot state = Snapshot.empty();
            @Override public Snapshot load() { return state; }
            @Override public void save(Snapshot snapshot) { state = snapshot; }
        });
    }

    /**
     * Idempotently materializes the standing Position contract and binds an admitted Worker participation to it.
     * Conflicting redefinition fails closed: a staffing policy must version/change the Position instead.
     */
    public synchronized WorkerPositionBinding ensureConstitution(
            AutonomousStaffingPolicy policy,
            Instant at) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(at, "at");
        AutonomousStaffingPolicy.FormationSpec formation = policy.formationSpec();
        AutonomousStaffingPolicy.PositionContractSpec spec = policy.positionContractSpec();

        PositionOperatingContract candidate = toContract(policy, formation, spec, at);
        PositionOperatingContract existing = contracts.get(formation.positionRef());
        if (existing == null) {
            contracts.put(candidate.positionRef(), candidate);
        } else if (!sameStandingContract(existing, candidate)) {
            throw new IllegalStateException("position-operating-contract-conflict:" + formation.positionRef());
        } else if (!existing.addressAliases().equals(candidate.addressAliases())) {
            // Address aliases are addressing metadata, not part of the standing mission/authority envelope, so a
            // policy may declare or change them on an existing Position without versioning it. The standing contract
            // (id, effective time, evidence) is kept; only the declared aliases follow the current policy.
            contracts.put(existing.positionRef(), existing.withAddressAliases(candidate.addressAliases()));
        }

        String key = bindingKey(formation.workerId(), formation.participationId());
        WorkerPositionBinding candidateBinding = new WorkerPositionBinding(
                formation.workerId(),
                formation.participationId(),
                formation.positionRef(),
                candidate.contractId(),
                formation.roleRef(),
                formation.organizationRef(),
                at,
                List.of(
                        "staffing-policy:" + policy.capabilityRef(),
                        "position-contract:" + candidate.contractId(),
                        "authority-envelope:" + formation.authorityEnvelopeRef(),
                        "qualification-evidence:" + formation.qualificationEvidenceRef()));
        WorkerPositionBinding existingBinding = bindings.get(key);
        if (existingBinding == null) {
            bindings.put(key, candidateBinding);
        } else if (!sameBinding(existingBinding, candidateBinding)) {
            throw new IllegalStateException("worker-position-binding-conflict:" + formation.workerId());
        }
        persist();
        return bindings.get(key);
    }

    public synchronized Optional<PositionOperatingContract> contractForPosition(String positionRef) {
        return Optional.ofNullable(contracts.get(positionRef));
    }

    public synchronized Optional<WorkerPositionBinding> binding(String workerId, String participationId) {
        return Optional.ofNullable(bindings.get(bindingKey(workerId, participationId)));
    }

    public synchronized WorkerPositionBinding requireBinding(String workerId, String participationId) {
        return binding(workerId, participationId)
                .orElseThrow(() -> new IllegalStateException(
                        "worker-constitution-unbound:" + workerId + ":participation=" + participationId));
    }

    public synchronized PositionOperatingContract requireContractFor(String workerId, String participationId) {
        WorkerPositionBinding binding = requireBinding(workerId, participationId);
        PositionOperatingContract contract = contracts.get(binding.positionRef());
        if (contract == null || !contract.contractId().equals(binding.contractId())) {
            throw new IllegalStateException("worker-position-contract-missing:" + workerId);
        }
        return contract;
    }

    /**
     * Contextual, evidence-derived performance. This is deliberately current/period-bound rather than
     * an immutable Worker score.
     */
    public synchronized PerformanceEvaluation recordPerformance(
            String workerId,
            Instant periodStart,
            Instant periodEnd,
            long ownedObjectives,
            long completedObjectives,
            long assignments,
            long actionRecords,
            long successfulActions,
            long failedActions,
            String evaluatorRef,
            List<String> evidenceReferences) {
        require(workerId, "workerId");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        if (periodEnd.isBefore(periodStart)) throw new IllegalArgumentException("performance period invalid");
        require(evaluatorRef, "evaluatorRef");
        if (ownedObjectives < 0 || completedObjectives < 0 || assignments < 0
                || actionRecords < 0 || successfulActions < 0 || failedActions < 0) {
            throw new IllegalArgumentException("performance counters must be non-negative");
        }
        if (completedObjectives > ownedObjectives || successfulActions + failedActions > actionRecords) {
            throw new IllegalArgumentException("performance counters inconsistent");
        }
        double objectiveCompletionRatio = ownedObjectives == 0 ? 1.0 : completedObjectives / (double)ownedObjectives;
        double actionSuccessRatio = actionRecords == 0 ? 1.0 : successfulActions / (double)actionRecords;
        PerformanceEvaluation value = new PerformanceEvaluation(
                "performance:" + workerId + ":current",
                workerId,
                periodStart,
                periodEnd,
                ownedObjectives,
                completedObjectives,
                assignments,
                actionRecords,
                successfulActions,
                failedActions,
                objectiveCompletionRatio,
                actionSuccessRatio,
                evaluatorRef,
                evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences),
                Instant.now());
        performance.put(workerId, value);
        persist();
        return value;
    }

    public synchronized Optional<PerformanceEvaluation> performance(String workerId) {
        return Optional.ofNullable(performance.get(workerId));
    }

    /**
     * Idempotently records attributable operating experience from observed action evidence.
     */
    public synchronized ExperienceRecord observeAction(
            String workerId,
            String actionRef,
            boolean success,
            String summary,
            Instant observedAt,
            List<String> evidenceReferences) {
        require(workerId, "workerId");
        require(actionRef, "actionRef");
        require(summary, "summary");
        Objects.requireNonNull(observedAt, "observedAt");
        String id = "experience:" + safe(workerId) + ":" + safe(actionRef) + ":"
                + Integer.toUnsignedString(Objects.hash(workerId, actionRef, success, summary, observedAt));
        ExperienceRecord existing = experiences.get(id);
        if (existing != null) return existing;

        ExperienceRecord value = new ExperienceRecord(
                id,
                workerId,
                actionRef,
                success ? "SUCCESS" : "FAILED",
                summary,
                observedAt,
                evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences));
        experiences.put(id, value);
        deriveRecoveryLearning(workerId, actionRef);
        persist();
        return value;
    }

    public synchronized List<ExperienceRecord> experiences(String workerId) {
        return experiences.values().stream()
                .filter(value -> value.workerId().equals(workerId))
                .sorted(Comparator.comparing(ExperienceRecord::observedAt))
                .toList();
    }

    public synchronized List<LearningRecord> learning(String workerId) {
        return learning.values().stream()
                .filter(value -> value.workerId().equals(workerId))
                .sorted(Comparator.comparing(LearningRecord::learnedAt))
                .toList();
    }

    /**
     * Materialized context passed into cognition. It contains standing contract + current evaluated reality,
     * not merely a role name or runtime profile.
     */
    public synchronized ConstitutionContext contextFor(String workerId, String participationId) {
        PositionOperatingContract contract = requireContractFor(workerId, participationId);
        WorkerPositionBinding binding = requireBinding(workerId, participationId);
        PerformanceEvaluation evaluation = performance.get(workerId);
        List<ExperienceRecord> recentExperience = experiences(workerId).stream()
                .skip(Math.max(0, experiences(workerId).size() - 8L))
                .toList();
        List<LearningRecord> recentLearning = learning(workerId).stream()
                .skip(Math.max(0, learning(workerId).size() - 6L))
                .toList();

        LinkedHashSet<String> evidence = new LinkedHashSet<>(contract.evidenceReferences());
        evidence.addAll(binding.evidenceReferences());
        if (evaluation != null) evidence.addAll(evaluation.evidenceReferences());
        recentExperience.forEach(value -> evidence.addAll(value.evidenceReferences()));
        recentLearning.forEach(value -> evidence.addAll(value.evidenceReferences()));

        String text = """
                MATERIALIZED WORKER / POSITION OPERATING CONTRACT
                contract_id=%s
                organization_ref=%s
                position_ref=%s
                role_ref=%s
                mission=%s
                responsibilities=%s
                reporting_lines=%s
                capability_requirements=%s
                authority_scopes=%s
                resource_scopes=%s
                escalation_routes=%s
                success_measures=%s
                decision_rights=%s
                operating_coverage=%s
                working_time_zone=%s
                max_concurrent_assignments=%s

                CURRENT CONTEXTUAL PERFORMANCE
                %s

                RECENT ATTRIBUTABLE EXPERIENCE
                %s

                EVIDENCE-DERIVED LEARNING
                %s
                """.formatted(
                contract.contractId(),
                contract.organizationRef(),
                contract.positionRef(),
                contract.roleRef(),
                contract.mission(),
                contract.responsibilities(),
                contract.reportingLines(),
                contract.capabilityRequirements(),
                contract.authorityScopes(),
                contract.resourceScopes(),
                contract.escalationRoutes(),
                contract.successMeasures(),
                contract.decisionRights(),
                contract.operatingCoverage(),
                contract.workingTimeZone(),
                contract.maxConcurrentAssignments(),
                evaluation == null ? "none recorded yet" : evaluation,
                recentExperience.isEmpty() ? "none recorded yet" : recentExperience,
                recentLearning.isEmpty() ? "none recorded yet" : recentLearning);
        return new ConstitutionContext(contract, binding, evaluation, recentExperience, recentLearning,
                text, List.copyOf(evidence));
    }

    private void deriveRecoveryLearning(String workerId, String actionRef) {
        List<ExperienceRecord> actionHistory = experiences.values().stream()
                .filter(value -> value.workerId().equals(workerId) && value.actionRef().equals(actionRef))
                .sorted(Comparator.comparing(ExperienceRecord::observedAt))
                .toList();
        ExperienceRecord firstFailure = null;
        ExperienceRecord laterSuccess = null;
        for (ExperienceRecord value : actionHistory) {
            if ("FAILED".equals(value.outcome()) && firstFailure == null) firstFailure = value;
            if (firstFailure != null && "SUCCESS".equals(value.outcome())
                    && value.observedAt().isAfter(firstFailure.observedAt())) {
                laterSuccess = value;
                break;
            }
        }
        if (firstFailure == null || laterSuccess == null) return;

        String id = "learning:" + safe(workerId) + ":recovery:" + safe(actionRef);
        if (learning.containsKey(id)) return;
        List<String> evidence = new ArrayList<>();
        evidence.addAll(firstFailure.evidenceReferences());
        evidence.addAll(laterSuccess.evidenceReferences());
        learning.put(id, new LearningRecord(
                id,
                workerId,
                "RECOVERY_PATTERN",
                "Observed governed recovery for " + actionRef
                        + ": a failed attempt was followed by a successful observed attempt. "
                        + "Future work should inspect/change state before justified retry rather than repeat blindly.",
                List.of(firstFailure.experienceId(), laterSuccess.experienceId()),
                List.copyOf(new LinkedHashSet<>(evidence)),
                laterSuccess.observedAt()));
    }

    private static PositionOperatingContract toContract(
            AutonomousStaffingPolicy policy,
            AutonomousStaffingPolicy.FormationSpec formation,
            AutonomousStaffingPolicy.PositionContractSpec spec,
            Instant at) {
        List<ReportingLine> reporting = spec.reportingLines().stream()
                .map(value -> new ReportingLine(value.relationshipType(), value.targetRef(), value.scope()))
                .toList();
        List<ResourceScope> resources = spec.resourceScopes().stream()
                .map(value -> new ResourceScope(value.resourceRef(), value.limitRef()))
                .toList();
        List<EscalationRoute> escalation = spec.escalationRoutes().stream()
                .map(value -> new EscalationRoute(value.category(), value.targetRef(), value.trigger()))
                .toList();
        List<SuccessMeasure> measures = spec.successMeasures().stream()
                .map(value -> new SuccessMeasure(value.measureRef(), value.description(), value.target()))
                .toList();

        return new PositionOperatingContract(
                "position-contract:" + formation.positionRef() + ":v1",
                formation.organizationRef(),
                formation.positionRef(),
                formation.roleRef(),
                spec.mission(),
                spec.responsibilities(),
                reporting,
                spec.capabilityRequirements(),
                spec.authorityScopes(),
                resources,
                escalation,
                measures,
                spec.decisionRights(),
                spec.operatingCoverage(),
                spec.workingTimeZone(),
                spec.maxConcurrentAssignments(),
                at,
                List.of(
                        "staffing-policy:" + policy.capabilityRef(),
                        "capability-evidence:" + formation.capabilityEvidenceRef(),
                        "qualification-evidence:" + formation.qualificationEvidenceRef(),
                        "authority-envelope:" + formation.authorityEnvelopeRef(),
                        "cost-limit:" + formation.costLimitRef(),
                        "lifecycle:" + formation.lifecycleRef()),
                spec.addressAliases());
    }

    private static boolean sameStandingContract(PositionOperatingContract left, PositionOperatingContract right) {
        return left.organizationRef().equals(right.organizationRef())
                && left.positionRef().equals(right.positionRef())
                && left.roleRef().equals(right.roleRef())
                && left.mission().equals(right.mission())
                && left.responsibilities().equals(right.responsibilities())
                && left.reportingLines().equals(right.reportingLines())
                && left.capabilityRequirements().equals(right.capabilityRequirements())
                && left.authorityScopes().equals(right.authorityScopes())
                && left.resourceScopes().equals(right.resourceScopes())
                && left.escalationRoutes().equals(right.escalationRoutes())
                && left.successMeasures().equals(right.successMeasures())
                && left.decisionRights().equals(right.decisionRights())
                && left.operatingCoverage().equals(right.operatingCoverage())
                && left.workingTimeZone().equals(right.workingTimeZone())
                && left.maxConcurrentAssignments() == right.maxConcurrentAssignments();
    }

    private static boolean sameBinding(WorkerPositionBinding left, WorkerPositionBinding right) {
        return left.workerId().equals(right.workerId())
                && left.participationId().equals(right.participationId())
                && left.positionRef().equals(right.positionRef())
                && left.contractId().equals(right.contractId())
                && left.roleRef().equals(right.roleRef())
                && left.organizationRef().equals(right.organizationRef());
    }

    private synchronized void persist() {
        store.save(new WorkerConstitutionStateStore.Snapshot(
                List.copyOf(contracts.values()),
                List.copyOf(bindings.values()),
                List.copyOf(performance.values()),
                List.copyOf(experiences.values()),
                List.copyOf(learning.values())));
    }

    private static String bindingKey(String workerId, String participationId) {
        require(workerId, "workerId");
        require(participationId, "participationId");
        return workerId + "|" + participationId;
    }

    private static String safe(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._:-]+", "-");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }

    public record ReportingLine(String relationshipType, String targetRef, String scope) {
        public ReportingLine {
            require(relationshipType, "relationshipType");
            require(targetRef, "targetRef");
            require(scope, "scope");
        }
    }

    public record ResourceScope(String resourceRef, String limitRef) {
        public ResourceScope {
            require(resourceRef, "resourceRef");
            require(limitRef, "limitRef");
        }
    }

    public record EscalationRoute(String category, String targetRef, String trigger) {
        public EscalationRoute {
            require(category, "category");
            require(targetRef, "targetRef");
            require(trigger, "trigger");
        }
    }

    public record SuccessMeasure(String measureRef, String description, String target) {
        public SuccessMeasure {
            require(measureRef, "measureRef");
            require(description, "description");
            require(target, "target");
        }
    }

    public record PositionOperatingContract(
            String contractId,
            String organizationRef,
            String positionRef,
            String roleRef,
            String mission,
            List<String> responsibilities,
            List<ReportingLine> reportingLines,
            List<String> capabilityRequirements,
            List<String> authorityScopes,
            List<ResourceScope> resourceScopes,
            List<EscalationRoute> escalationRoutes,
            List<SuccessMeasure> successMeasures,
            List<String> decisionRights,
            String operatingCoverage,
            String workingTimeZone,
            int maxConcurrentAssignments,
            Instant effectiveAt,
            List<String> evidenceReferences,
            List<String> addressAliases) {
        public PositionOperatingContract {
            require(contractId, "contractId");
            require(organizationRef, "organizationRef");
            require(positionRef, "positionRef");
            require(roleRef, "roleRef");
            require(mission, "mission");
            responsibilities = List.copyOf(responsibilities);
            reportingLines = List.copyOf(reportingLines);
            capabilityRequirements = List.copyOf(capabilityRequirements);
            authorityScopes = List.copyOf(authorityScopes);
            resourceScopes = List.copyOf(resourceScopes);
            escalationRoutes = List.copyOf(escalationRoutes);
            successMeasures = List.copyOf(successMeasures);
            decisionRights = List.copyOf(decisionRights);
            require(operatingCoverage, "operatingCoverage");
            require(workingTimeZone, "workingTimeZone");
            if (maxConcurrentAssignments < 1) throw new IllegalArgumentException("maxConcurrentAssignments must be positive");
            Objects.requireNonNull(effectiveAt, "effectiveAt");
            evidenceReferences = List.copyOf(evidenceReferences);
            // Contracts persisted before address aliases existed deserialize with no aliases.
            addressAliases = addressAliases == null ? List.of() : List.copyOf(addressAliases);
        }

        public PositionOperatingContract withAddressAliases(List<String> aliases) {
            return new PositionOperatingContract(contractId, organizationRef, positionRef, roleRef, mission,
                    responsibilities, reportingLines, capabilityRequirements, authorityScopes, resourceScopes,
                    escalationRoutes, successMeasures, decisionRights, operatingCoverage, workingTimeZone,
                    maxConcurrentAssignments, effectiveAt, evidenceReferences, aliases);
        }
    }

    public record WorkerPositionBinding(
            String workerId,
            String participationId,
            String positionRef,
            String contractId,
            String roleRef,
            String organizationRef,
            Instant effectiveAt,
            List<String> evidenceReferences) {
        public WorkerPositionBinding {
            require(workerId, "workerId");
            require(participationId, "participationId");
            require(positionRef, "positionRef");
            require(contractId, "contractId");
            require(roleRef, "roleRef");
            require(organizationRef, "organizationRef");
            Objects.requireNonNull(effectiveAt, "effectiveAt");
            evidenceReferences = List.copyOf(evidenceReferences);
        }
    }

    public record PerformanceEvaluation(
            String evaluationId,
            String workerId,
            Instant periodStart,
            Instant periodEnd,
            long ownedObjectives,
            long completedObjectives,
            long assignments,
            long actionRecords,
            long successfulActions,
            long failedActions,
            double objectiveCompletionRatio,
            double actionSuccessRatio,
            String evaluatorRef,
            List<String> evidenceReferences,
            Instant evaluatedAt) {
        public PerformanceEvaluation {
            require(evaluationId, "evaluationId");
            require(workerId, "workerId");
            Objects.requireNonNull(periodStart, "periodStart");
            Objects.requireNonNull(periodEnd, "periodEnd");
            require(evaluatorRef, "evaluatorRef");
            evidenceReferences = List.copyOf(evidenceReferences);
            Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        }
    }

    public record ExperienceRecord(
            String experienceId,
            String workerId,
            String actionRef,
            String outcome,
            String statement,
            Instant observedAt,
            List<String> evidenceReferences) {
        public ExperienceRecord {
            require(experienceId, "experienceId");
            require(workerId, "workerId");
            require(actionRef, "actionRef");
            require(outcome, "outcome");
            require(statement, "statement");
            Objects.requireNonNull(observedAt, "observedAt");
            evidenceReferences = List.copyOf(evidenceReferences);
        }
    }

    public record LearningRecord(
            String learningId,
            String workerId,
            String type,
            String lesson,
            List<String> experienceReferences,
            List<String> evidenceReferences,
            Instant learnedAt) {
        public LearningRecord {
            require(learningId, "learningId");
            require(workerId, "workerId");
            require(type, "type");
            require(lesson, "lesson");
            experienceReferences = List.copyOf(experienceReferences);
            evidenceReferences = List.copyOf(evidenceReferences);
            Objects.requireNonNull(learnedAt, "learnedAt");
        }
    }

    public record ConstitutionContext(
            PositionOperatingContract contract,
            WorkerPositionBinding binding,
            PerformanceEvaluation performance,
            List<ExperienceRecord> recentExperience,
            List<LearningRecord> recentLearning,
            String renderedContext,
            List<String> evidenceReferences) {
        public ConstitutionContext {
            Objects.requireNonNull(contract, "contract");
            Objects.requireNonNull(binding, "binding");
            recentExperience = List.copyOf(recentExperience);
            recentLearning = List.copyOf(recentLearning);
            require(renderedContext, "renderedContext");
            evidenceReferences = List.copyOf(evidenceReferences);
        }
    }
}
