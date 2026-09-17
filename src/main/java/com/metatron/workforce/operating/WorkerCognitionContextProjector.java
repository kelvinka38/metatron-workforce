package com.metatron.workforce.operating;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.phase5.WorkSchedule;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Builds the bounded, assignment-scoped projection of a durable Worker runtime Constitution that
 * may enter cognition. The durable RuntimeConstitution remains the complete institutional read
 * model; unrelated historical relationships are kept there by reference instead of being dumped
 * into every model prompt.
 */
public final class WorkerCognitionContextProjector {
    public static final int MAX_CONTEXT_CHARS = 6_000;
    private static final int MAX_RECENT_ATTEMPTS = 6;
    private static final int MAX_RECENT_EXPERIENCE = 2;
    private static final int MAX_RECENT_LEARNING = 2;

    private WorkerCognitionContextProjector() {}

    public static String forAssignment(
            WorkerConstitutionRuntimeMaterializer.RuntimeConstitution snapshot,
            String assignmentReference) {
        Objects.requireNonNull(snapshot, "snapshot");
        require(assignmentReference, "assignmentReference");

        WorkforceCoreService.Assignment assignment = snapshot.assignments().stream()
                .filter(value -> assignmentReference.equals(value.assignmentId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "worker-cognition-assignment-missing:" + snapshot.workerId() + ":" + assignmentReference));

        List<WorkforceCoreService.CapacityReservation> reservations = snapshot.capacityReservations().stream()
                .filter(value -> assignmentReference.equals(value.assignmentId()))
                .sorted(Comparator.comparing(WorkforceCoreService.CapacityReservation::createdAt).reversed())
                .toList();
        List<WorkSchedule> schedules = snapshot.schedules().stream()
                .filter(value -> assignmentReference.equals(value.assignmentRef()))
                .sorted(Comparator.comparing(WorkSchedule::start).reversed())
                .toList();
        List<ExecutionAttempt> attempts = snapshot.executionAttribution().stream()
                .filter(value -> assignmentReference.equals(value.assignmentRef()))
                .sorted(Comparator.comparing(ExecutionAttempt::updatedAt).reversed())
                .limit(MAX_RECENT_ATTEMPTS)
                .toList();

        WorkerConstitutionService.PositionOperatingContract contract = snapshot.positionContract();
        WorkerConstitutionService.PerformanceEvaluation performance = snapshot.performance();
        List<WorkerConstitutionService.ExperienceRecord> experience = snapshot.recentExperience().stream()
                .skip(Math.max(0, snapshot.recentExperience().size() - MAX_RECENT_EXPERIENCE))
                .toList();
        List<WorkerConstitutionService.LearningRecord> learning = snapshot.recentLearning().stream()
                .skip(Math.max(0, snapshot.recentLearning().size() - MAX_RECENT_LEARNING))
                .toList();

        StringBuilder out = new StringBuilder(4_096);
        line(out, "WORKER_COGNITION_CONTEXT_V1", "assignment-scoped bounded projection");
        line(out, "source_snapshot_ref", snapshot.snapshotId());
        line(out, "source_materialized_at", snapshot.materializedAt());
        line(out, "history_policy", "full durable Constitution remains in source snapshot; unrelated history omitted from cognition");
        line(out, "worker", snapshot.workerId() + ":" + snapshot.worker().status());
        line(out, "participant", snapshot.participant().participantId());
        line(out, "participation", snapshot.participation().participationId()
                + ":org=" + snapshot.participation().organizationRef()
                + ":position=" + snapshot.participation().positionRef()
                + ":role=" + snapshot.participation().roleRef());
        line(out, "contract", contract.contractId());
        line(out, "mission", bounded(contract.mission(), 500));
        line(out, "responsibilities", compact(contract.responsibilities(), 8, 220, Function.identity()));
        line(out, "reporting", compact(contract.reportingLines(), 6, 220, value ->
                value.relationshipType() + "->" + value.targetRef() + ":" + value.scope()));
        line(out, "capability_requirements", compact(contract.capabilityRequirements(), 10, 180, Function.identity()));
        line(out, "authority_scopes", compact(contract.authorityScopes(), 8, 180, Function.identity()));
        line(out, "resource_scopes", compact(contract.resourceScopes(), 8, 180, value ->
                value.resourceRef() + ":" + value.limitRef()));
        line(out, "escalation_routes", compact(contract.escalationRoutes(), 6, 220, value ->
                value.category() + "->" + value.targetRef() + ":" + value.trigger()));
        line(out, "success_measures", compact(contract.successMeasures(), 6, 180, value ->
                value.measureRef() + ":target=" + value.target()));
        line(out, "decision_rights", compact(contract.decisionRights(), 8, 180, Function.identity()));
        line(out, "coverage", bounded(contract.operatingCoverage(), 220)
                + ":tz=" + contract.workingTimeZone()
                + ":max_concurrent=" + contract.maxConcurrentAssignments());
        line(out, "capabilities", compact(snapshot.capabilities(), 20, 140, value ->
                value.capabilityRef() + "@" + value.level()));
        line(out, "qualifications", compact(snapshot.qualifications(), 20, 140,
                WorkforceCoreService.Qualification::qualificationRef));
        line(out, "capacity", "available=" + snapshot.capacity().available()
                + ":configured=" + snapshot.capacity().configuredCapacity()
                + ":reserved=" + snapshot.capacity().reservedCapacity()
                + ":remaining=" + snapshot.capacity().remainingCapacity());
        line(out, "assignment", assignment.assignmentId()
                + ":objective=" + assignment.objectiveRef()
                + ":status=" + assignment.status()
                + ":authority=" + assignment.authorityRef()
                + ":authorization=" + assignment.authorizationRef());
        line(out, "assignment_reservations", compact(reservations, 4, 180, value ->
                value.reservationId() + ":" + value.status() + ":capacity=" + value.capacity()));
        line(out, "assignment_schedules", compact(schedules, 4, 220, value ->
                value.scheduleId() + ":" + value.status() + ":" + value.start() + ".." + value.end()));
        line(out, "runtime", snapshot.runtime().profileRef()
                + ":binding_capability=" + snapshot.runtime().bindingCapabilityRef()
                + ":id=" + blankAsNone(snapshot.runtime().runtimeId())
                + ":state=" + snapshot.runtime().runtimeState());
        line(out, "assignment_attempts", compact(attempts, MAX_RECENT_ATTEMPTS, 220, value ->
                value.attemptId() + ":step=" + value.stepId() + ":status=" + value.status()
                        + ":n=" + value.attemptNumber()));
        line(out, "performance", performance == null ? "none" : "evaluation=" + performance.evaluationId()
                + ":objective_completion_ratio=" + performance.objectiveCompletionRatio()
                + ":action_success_ratio=" + performance.actionSuccessRatio()
                + ":evaluated_at=" + performance.evaluatedAt());
        line(out, "recent_experience", compact(experience, MAX_RECENT_EXPERIENCE, 420, value ->
                value.experienceId() + ":action=" + value.actionRef() + ":outcome=" + value.outcome()
                        + ":statement=" + value.statement()));
        line(out, "recent_learning", compact(learning, MAX_RECENT_LEARNING, 420, value ->
                value.learningId() + ":type=" + value.type() + ":lesson=" + value.lesson()));

        String rendered = out.toString();
        if (rendered.length() > MAX_CONTEXT_CHARS) {
            throw new IllegalStateException("worker-cognition-context-budget-exceeded:chars="
                    + rendered.length() + ":limit=" + MAX_CONTEXT_CHARS + ":snapshot=" + snapshot.snapshotId()
                    + ":assignment=" + assignmentReference);
        }
        return rendered;
    }

    private static <T> String compact(List<T> values, int maxItems, int maxItemChars, Function<T, String> render) {
        if (values == null || values.isEmpty()) return "none";
        int include = Math.min(values.size(), maxItems);
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < include; i++) {
            if (i > 0) out.append(" | ");
            out.append(bounded(render.apply(values.get(i)), maxItemChars));
        }
        if (values.size() > include) out.append(" | ...[OMITTED_COUNT=").append(values.size() - include).append(']');
        return out.append(']').toString();
    }

    private static String bounded(String value, int maxChars) {
        String normalized = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= maxChars) return normalized;
        return normalized.substring(0, maxChars)
                + "...[EXPLICITLY_COMPACTED original_chars=" + normalized.length() + "]";
    }

    private static void line(StringBuilder out, String key, Object value) {
        out.append(key).append('=').append(value).append('\n');
    }

    private static String blankAsNone(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }
}
