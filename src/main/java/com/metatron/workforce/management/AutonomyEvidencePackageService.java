package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.observation.ObservationClosureService;
import com.metatron.workforce.observation.ObservationReport;
import com.metatron.workforce.observation.ObservationRequirement;
import com.metatron.workforce.workplace.WorkplaceContinuityRecord;
import com.metatron.workforce.workplace.WorkplaceContinuityService;
import com.metatron.workforce.workplace.WorkplaceDelivery;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Canonical, reconstructable Objective progress/closure package. No model-generated state is accepted here. */
public final class AutonomyEvidencePackageService {
    private final ManagementAutonomyService management;
    private final AutonomyCoordinationService coordination;
    private final AutonomySafetyService safety;
    private final ObservationClosureService observation;
    private final WorkforceCoreService core;
    private final ExecutionAttemptService executionAttempts;
    private final WorkplaceContinuityService workplace;

    public AutonomyEvidencePackageService(ManagementAutonomyService management,
                                          AutonomyCoordinationService coordination,
                                          AutonomySafetyService safety,
                                          ObservationClosureService observation,
                                          WorkforceCoreService core,
                                          ExecutionAttemptService executionAttempts,
                                          WorkplaceContinuityService workplace) {
        this.management = Objects.requireNonNull(management);
        this.coordination = Objects.requireNonNull(coordination);
        this.safety = Objects.requireNonNull(safety);
        this.observation = Objects.requireNonNull(observation);
        this.core = Objects.requireNonNull(core);
        this.executionAttempts = Objects.requireNonNull(executionAttempts);
        this.workplace = Objects.requireNonNull(workplace);
    }

    public ObjectiveEvidencePackage packageFor(String objectiveId) {
        ManagementObjective objective = management.get(objectiveId);
        AutonomousObjectiveWork work = management.findAutonomousWork(objectiveId).orElse(null);
        AutonomySafetyState safetyState = safety.ensureObjective(objectiveId);
        List<DurableWorkGraph> graphs = coordination.graphHistory(objectiveId);
        List<DurableDispatch> dispatches = coordination.dispatches().stream()
                .filter(dispatch -> dispatch.objectiveId().equals(objectiveId)).toList();
        List<AutonomyCoordinationStateStore.DeadLetter> deadLetters = coordination.deadLetters().stream()
                .filter(dead -> dead.objectiveId().equals(objectiveId)).toList();
        List<ExecutionAttempt> attempts = executionAttempts.all().stream()
                .filter(attempt -> attempt.objectiveId().equals(objectiveId)).toList();
        List<WorkforceCoreService.Assignment> assignments = core.allAssignments().stream()
                .filter(assignment -> assignment.objectiveRef().equals(objectiveId)).toList();
        List<String> workers = assignments.stream().map(WorkforceCoreService.Assignment::workerId).distinct().toList();
        List<ObservationRequirement> requirements = observation.requirements(objectiveId);
        List<ObservationReport> reports = observation.reports(objectiveId);
        ObservationClosureService.Verdict observationVerdict = observation.verdict(objectiveId);

        WorkplaceContinuityRecord continuity = null;
        List<WorkplaceDelivery> deliveries = List.of();
        try {
            continuity = workplace.continuity(objectiveId);
            deliveries = workplace.deliveries(objectiveId);
        } catch (IllegalArgumentException ignored) {
            // Compatibility/manual Objectives may predate Workplace continuity binding.
        }

        Instant end = objective.updatedAt();
        long durationMillis = Math.max(0L, Duration.between(objective.createdAt(), end).toMillis());
        String nextTransition = deriveNextTransition(objective, work, safetyState, observationVerdict, deadLetters);
        String remainingRisk = deriveRemainingRisk(objective, safetyState, observationVerdict, deadLetters);

        return new ObjectiveEvidencePackage(
                objective,
                work,
                safetyState,
                graphs,
                dispatches,
                deadLetters,
                assignments,
                workers,
                attempts,
                requirements,
                reports,
                observationVerdict,
                continuity,
                deliveries,
                management.history(objectiveId),
                management.outbox().stream().filter(message -> message.subjectId().equals(objectiveId)).toList(),
                safetyState.consumedCostUnits(),
                "AUTONOMY_POLICY_COST_UNITS; authoritative accounting remains Economy-owned",
                durationMillis,
                remainingRisk,
                nextTransition,
                objective.status() == ManagementObjective.Status.COMPLETED
                        && observationVerdict == ObservationClosureService.Verdict.PASSED);
    }

    private static String deriveNextTransition(ManagementObjective objective, AutonomousObjectiveWork work,
                                               AutonomySafetyState safety,
                                               ObservationClosureService.Verdict observationVerdict,
                                               List<AutonomyCoordinationStateStore.DeadLetter> deadLetters) {
        if (safety.controlStatus() == AutonomySafetyState.ControlStatus.CANCELLED) return "TERMINAL_CONTROL_CANCELLED";
        if (safety.controlStatus() == AutonomySafetyState.ControlStatus.PAUSED) return "AUTHORIZED_RESUME_OR_AMENDMENT_REPLAN";
        if (safety.authorityRevoked()) return "AUTHORITY_RESTORATION_OR_CANCELLATION";
        if (!deadLetters.isEmpty()) return "DEAD_LETTER_RECONCILIATION";
        if (objective.status() == ManagementObjective.Status.BLOCKED) return "RECOVERY_REPLAN_OR_ESCALATION";
        if (work == null) return "MANAGEMENT_PLANNING";
        if (work.completedStepIds().size() < work.plannedWork().size()) return "READY_WORK_DISPATCH_OR_RECOVERY";
        if (observationVerdict == ObservationClosureService.Verdict.PENDING) return "OBSERVATION";
        if (observationVerdict == ObservationClosureService.Verdict.FAILED
                || observationVerdict == ObservationClosureService.Verdict.INCONCLUSIVE) return "REPLAN_OR_ESCALATION";
        if (objective.status() == ManagementObjective.Status.COMPLETED) return "DELIVERY_LEARNING";
        return "OBJECTIVE_CLOSURE";
    }

    private static String deriveRemainingRisk(ManagementObjective objective, AutonomySafetyState safety,
                                              ObservationClosureService.Verdict observationVerdict,
                                              List<AutonomyCoordinationStateStore.DeadLetter> deadLetters) {
        if (safety.authorityRevoked()) return "AUTHORITY_REVOKED";
        if (!deadLetters.isEmpty()) return "UNRECONCILED_DEAD_LETTER";
        if (observationVerdict != ObservationClosureService.Verdict.PASSED) return "UNVERIFIED_OUTCOME";
        if (objective.status() == ManagementObjective.Status.BLOCKED
                || objective.status() == ManagementObjective.Status.ESCALATED) return "OBJECTIVE_BLOCKED";
        return "NONE_RECORDED_WITHIN_CURRENT_OPERATIONAL_SCOPE";
    }

    public record ObjectiveEvidencePackage(
            ManagementObjective objective,
            AutonomousObjectiveWork work,
            AutonomySafetyState safety,
            List<DurableWorkGraph> graphHistory,
            List<DurableDispatch> dispatches,
            List<AutonomyCoordinationStateStore.DeadLetter> deadLetters,
            List<WorkforceCoreService.Assignment> assignments,
            List<String> workerIds,
            List<ExecutionAttempt> executionAttempts,
            List<ObservationRequirement> observationRequirements,
            List<ObservationReport> observationReports,
            ObservationClosureService.Verdict observationVerdict,
            WorkplaceContinuityRecord workplaceContinuity,
            List<WorkplaceDelivery> deliveries,
            List<ManagementAutonomyService.ManagementEvent> managementHistory,
            List<ManagementOutboxMessage> managementOutbox,
            double consumedCostUnits,
            String costBasis,
            long durationMillis,
            String remainingRisk,
            String nextTransition,
            boolean evidenceClosed) {}
}
