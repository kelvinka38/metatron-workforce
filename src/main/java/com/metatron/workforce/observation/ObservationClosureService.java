package com.metatron.workforce.observation;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Workforce-facing coordinator for the independent Observation boundary.
 * It creates criterion requests and consumes reports; it never converts execution success into truth.
 */
public final class ObservationClosureService {
    public enum Verdict { PENDING, PASSED, FAILED, INCONCLUSIVE }

    private final Map<String, ObservationRequirement> requirements = new LinkedHashMap<>();
    private final Map<String, ObservationReport> reportsByRequirement = new LinkedHashMap<>();
    private final ObservationStateStore store;
    private final List<ObservationVerifier> verifiers;

    public ObservationClosureService() {
        this(new InMemoryObservationStateStore(), List.of());
    }

    public ObservationClosureService(ObservationStateStore store, List<ObservationVerifier> verifiers) {
        this.store = Objects.requireNonNull(store, "store");
        this.verifiers = List.copyOf(verifiers == null ? List.of() : verifiers);
        ObservationStateStore.Snapshot snapshot = store.load();
        requirements.putAll(snapshot.requirements());
        reportsByRequirement.putAll(snapshot.reportsByRequirement());
    }

    public synchronized List<ObservationRequirement> ensureRequirements(
            String objectiveId, List<ExecutionWorkSpec> plan, Instant at) {
        require(objectiveId, "objectiveId");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(at, "at");
        if (plan.isEmpty()) throw new IllegalArgumentException("Observation requires a non-empty Work plan");

        List<ObservationRequirement> objectiveRequirements = requirements(objectiveId);
        if (!objectiveRequirements.isEmpty()) return objectiveRequirements;

        for (ExecutionWorkSpec step : plan) {
            if (!step.verifiable()) {
                throw new IllegalStateException("observation-requirements-missing:" + step.stepId());
            }
            for (int index = 0; index < step.acceptanceCriteria().size(); index++) {
                String criterionId = step.stepId() + ":criterion:" + (index + 1);
                String requirementId = objectiveId + ":observation:" + criterionId;
                ObservationRequirement requirement = new ObservationRequirement(
                        requirementId, objectiveId, step.stepId(), criterionId,
                        step.target(), step.acceptanceCriteria().get(index), step.evidenceRequirements(), at);
                requirements.put(requirementId, requirement);
            }
        }
        persist();
        return requirements(objectiveId);
    }

    /** Invokes only explicitly configured Observation adapters. No adapter means fail-closed pending verification. */
    public synchronized List<ObservationReport> observeAvailable(
            String objectiveId, List<String> executionEvidenceReferences, Instant at) {
        Objects.requireNonNull(executionEvidenceReferences, "executionEvidenceReferences");
        Objects.requireNonNull(at, "at");
        for (ObservationRequirement requirement : requirements(objectiveId)) {
            if (reportsByRequirement.containsKey(requirement.requirementId())) continue;
            for (ObservationVerifier verifier : verifiers) {
                if (!verifier.supports(requirement)) continue;
                verifier.observe(requirement, List.copyOf(executionEvidenceReferences), at)
                        .ifPresent(this::recordReport);
                if (reportsByRequirement.containsKey(requirement.requirementId())) break;
            }
        }
        return reports(objectiveId);
    }

    public synchronized ObservationReport recordReport(ObservationReport report) {
        Objects.requireNonNull(report, "report");
        ObservationRequirement requirement = requirements.get(report.requirementId());
        if (requirement == null) throw new IllegalArgumentException("unknown observation requirement: " + report.requirementId());
        if (!requirement.objectiveId().equals(report.objectiveId())) {
            throw new IllegalStateException("Observation report objective mismatch");
        }
        ObservationReport current = reportsByRequirement.get(report.requirementId());
        if (current != null) {
            if (current.reportId().equals(report.reportId())) return current;
            if (!report.observedAt().isAfter(current.observedAt())) {
                throw new IllegalStateException("stale Observation report fenced: " + report.reportId());
            }
        }
        reportsByRequirement.put(report.requirementId(), report);
        persist();
        return report;
    }

    public synchronized Verdict verdict(String objectiveId) {
        List<ObservationRequirement> expected = requirements(objectiveId);
        if (expected.isEmpty()) return Verdict.PENDING;
        List<ObservationReport> reports = reports(objectiveId);
        if (reports.size() != expected.size()) return Verdict.PENDING;
        if (reports.stream().anyMatch(r -> r.criterionResult() == ObservationReport.CriterionResult.FAIL)) {
            return Verdict.FAILED;
        }
        if (reports.stream().anyMatch(r -> r.criterionResult() == ObservationReport.CriterionResult.INCONCLUSIVE
                || r.quality() == ObservationReport.Quality.INSUFFICIENT)) {
            return Verdict.INCONCLUSIVE;
        }
        return reports.stream().allMatch(r -> r.criterionResult() == ObservationReport.CriterionResult.PASS)
                ? Verdict.PASSED : Verdict.PENDING;
    }

    public synchronized List<String> verifiedEvidenceReferences(String objectiveId) {
        if (verdict(objectiveId) != Verdict.PASSED) {
            throw new IllegalStateException("Objective has not passed Observation: " + objectiveId);
        }
        List<String> refs = new ArrayList<>();
        for (ObservationReport report : reports(objectiveId)) {
            refs.add("observation-report:" + report.reportId());
            report.evidenceReferences().stream().filter(ref -> !refs.contains(ref)).forEach(refs::add);
        }
        return List.copyOf(refs);
    }

    public synchronized List<ObservationRequirement> requirements(String objectiveId) {
        require(objectiveId, "objectiveId");
        return requirements.values().stream().filter(r -> r.objectiveId().equals(objectiveId))
                .sorted(Comparator.comparing(ObservationRequirement::criterionId)).toList();
    }

    public synchronized List<ObservationReport> reports(String objectiveId) {
        return requirements(objectiveId).stream().map(ObservationRequirement::requirementId)
                .map(reportsByRequirement::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing(ObservationReport::requirementId)).toList();
    }

    private void persist() {
        store.save(new ObservationStateStore.Snapshot(requirements, reportsByRequirement));
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }
}
