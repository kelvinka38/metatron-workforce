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
 * Verification recovery is bounded and durable: transient provider/evidence insufficiency may be retried,
 * while exhausted verification remains explicitly INCONCLUSIVE rather than looping forever.
 */
public final class ObservationClosureService {
    public enum Verdict { PENDING, PASSED, FAILED, INCONCLUSIVE }

    public static final int MAX_AUTONOMOUS_OBSERVATION_ATTEMPTS = 3;

    private final Map<String, ObservationRequirement> requirements = new LinkedHashMap<>();
    private final Map<String, ObservationReport> reportsByRequirement = new LinkedHashMap<>();
    private final Map<String, Integer> attemptsByRequirement = new LinkedHashMap<>();
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
        attemptsByRequirement.putAll(snapshot.attemptsByRequirement());
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
                attemptsByRequirement.putIfAbsent(requirementId, 0);
            }
        }
        persist();
        return requirements(objectiveId);
    }

    /**
     * Invokes only explicitly configured Observation adapters. No adapter means fail-closed verification.
     * A missing, INCONCLUSIVE or INSUFFICIENT report may be re-observed until the durable attempt ceiling.
     */
    public synchronized List<ObservationReport> observeAvailable(
            String objectiveId, List<String> executionEvidenceReferences, Instant at) {
        Objects.requireNonNull(executionEvidenceReferences, "executionEvidenceReferences");
        Objects.requireNonNull(at, "at");
        boolean changed = false;
        for (ObservationRequirement requirement : requirements(objectiveId)) {
            ObservationReport current = reportsByRequirement.get(requirement.requirementId());
            if (authoritativeTerminal(current)) continue;

            int attempts = attemptsByRequirement.getOrDefault(requirement.requirementId(), 0);
            if (attempts >= MAX_AUTONOMOUS_OBSERVATION_ATTEMPTS) continue;
            attemptsByRequirement.put(requirement.requirementId(), attempts + 1);
            changed = true;

            for (ObservationVerifier verifier : verifiers) {
                if (!verifier.supports(requirement)) continue;
                try {
                    verifier.observe(requirement, List.copyOf(executionEvidenceReferences), at)
                            .ifPresent(this::recordReport);
                } catch (RuntimeException transientVerifierFailure) {
                    // The attempt is durably counted. Another verifier or a later management pass may recover.
                    continue;
                }
                ObservationReport observed = reportsByRequirement.get(requirement.requirementId());
                if (authoritativeTerminal(observed) || observed != null) break;
            }
        }
        if (changed) persist();
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

        boolean retryablePending = false;
        for (ObservationRequirement requirement : expected) {
            ObservationReport report = reportsByRequirement.get(requirement.requirementId());
            int attempts = attemptsByRequirement.getOrDefault(requirement.requirementId(), 0);
            if (report == null) {
                if (attempts >= MAX_AUTONOMOUS_OBSERVATION_ATTEMPTS) return Verdict.INCONCLUSIVE;
                retryablePending = true;
                continue;
            }
            if (report.criterionResult() == ObservationReport.CriterionResult.FAIL) return Verdict.FAILED;
            if (report.criterionResult() == ObservationReport.CriterionResult.INCONCLUSIVE
                    || report.quality() == ObservationReport.Quality.INSUFFICIENT) {
                if (attempts >= MAX_AUTONOMOUS_OBSERVATION_ATTEMPTS) return Verdict.INCONCLUSIVE;
                retryablePending = true;
            }
        }
        if (retryablePending) return Verdict.PENDING;
        return expected.stream()
                .map(r -> reportsByRequirement.get(r.requirementId()))
                .allMatch(r -> r != null && r.criterionResult() == ObservationReport.CriterionResult.PASS)
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

    public synchronized int attempts(String requirementId) {
        require(requirementId, "requirementId");
        return attemptsByRequirement.getOrDefault(requirementId, 0);
    }

    public synchronized Map<String, Integer> attempts(String objectiveId) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (ObservationRequirement requirement : requirements(objectiveId)) {
            result.put(requirement.requirementId(), attempts(requirement.requirementId()));
        }
        return Map.copyOf(result);
    }

    private static boolean authoritativeTerminal(ObservationReport report) {
        if (report == null) return false;
        if (report.criterionResult() == ObservationReport.CriterionResult.FAIL) return true;
        return report.criterionResult() == ObservationReport.CriterionResult.PASS
                && report.quality() != ObservationReport.Quality.INSUFFICIENT;
    }

    private void persist() {
        store.save(new ObservationStateStore.Snapshot(requirements, reportsByRequirement, attemptsByRequirement));
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }
}
