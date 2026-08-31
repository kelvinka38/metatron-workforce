package com.metatron.workforce.management;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Evidence-bearing scheduler decision derived before any dispatch becomes active. */
public record AutonomySchedulingDecision(
        String decisionId,
        String objectiveId,
        int graphVersion,
        List<String> readyStepIds,
        List<String> selectedStepIds,
        List<String> staffingBootstrapStepIds,
        Map<String, String> projectedWorkerByStep,
        Map<String, String> deferredReasons,
        int structuralParallelismLimit,
        int remainingDispatchAttemptsBefore,
        double remainingCostUnitsBefore,
        double estimatedSelectedCostUnits,
        Instant deadline,
        AutonomySafetyState.RiskLevel maxRisk,
        Instant decidedAt) {
    public AutonomySchedulingDecision {
        if (decisionId == null || decisionId.isBlank()) throw new IllegalArgumentException("decisionId required");
        if (objectiveId == null || objectiveId.isBlank()) throw new IllegalArgumentException("objectiveId required");
        if (graphVersion < 1) throw new IllegalArgumentException("graphVersion must be positive");
        readyStepIds = List.copyOf(Objects.requireNonNull(readyStepIds, "readyStepIds"));
        selectedStepIds = List.copyOf(Objects.requireNonNull(selectedStepIds, "selectedStepIds"));
        staffingBootstrapStepIds = List.copyOf(Objects.requireNonNull(staffingBootstrapStepIds, "staffingBootstrapStepIds"));
        projectedWorkerByStep = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(projectedWorkerByStep, "projectedWorkerByStep")));
        deferredReasons = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(deferredReasons, "deferredReasons")));
        if (structuralParallelismLimit < 1) throw new IllegalArgumentException("structuralParallelismLimit must be positive");
        if (remainingDispatchAttemptsBefore < 0) throw new IllegalArgumentException("remainingDispatchAttemptsBefore invalid");
        if (!Double.isFinite(remainingCostUnitsBefore) || remainingCostUnitsBefore < 0) throw new IllegalArgumentException("remainingCostUnitsBefore invalid");
        if (!Double.isFinite(estimatedSelectedCostUnits) || estimatedSelectedCostUnits < 0) throw new IllegalArgumentException("estimatedSelectedCostUnits invalid");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(maxRisk, "maxRisk");
        Objects.requireNonNull(decidedAt, "decidedAt");
    }
}
