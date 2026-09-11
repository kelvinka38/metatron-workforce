package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Workforce-owned scheduling policy for one ready-set. Dependency readiness is supplied by the
 * versioned Work Graph; this service derives bounded dispatch parallelism from capability,
 * finite worker capacity, delegated budget/attempt ceiling, deadline and risk before effects run.
 */
public final class AutonomySchedulingService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AutonomySchedulingService.class);

    private final WorkforceCoreService core;
    private final AutonomySafetyService safety;
    private final AutonomySchedulingStateStore store;
    private final int maxParallelism;
    private final List<AutonomySchedulingDecision> decisions = new ArrayList<>();

    public AutonomySchedulingService(WorkforceCoreService core, AutonomySafetyService safety,
                                     AutonomySchedulingStateStore store, int maxParallelism) {
        this.core = Objects.requireNonNull(core, "core");
        this.safety = Objects.requireNonNull(safety, "safety");
        this.store = Objects.requireNonNull(store, "store");
        if (maxParallelism < 1) throw new IllegalArgumentException("maxParallelism must be positive");
        this.maxParallelism = maxParallelism;
        decisions.addAll(store.load().decisions());
    }

    public synchronized AutonomySchedulingDecision decide(
            String objectiveId,
            int graphVersion,
            List<DurableWorkGraph.Node> readyNodes,
            Map<String, AutonomousExecutionCapability> capabilities,
            Instant at) {
        require(objectiveId, "objectiveId");
        if (graphVersion < 1) throw new IllegalArgumentException("graphVersion must be positive");
        Objects.requireNonNull(readyNodes, "readyNodes");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(at, "at");

        List<DurableWorkGraph.Node> ready = readyNodes.stream()
                .sorted(Comparator.comparing(node -> node.spec().stepId()))
                .toList();
        AutonomySafetyState state = safety.ensureObjective(objectiveId, at);
        int remainingAttempts = Math.max(0,
                state.maxDispatchAttempts() - state.consumedDispatchAttempts());
        double remainingCost = Math.max(0.0,
                state.maxCostUnits() - state.consumedCostUnits());
        double projectedCost = 0.0;

        List<String> selected = new ArrayList<>();
        List<String> staffingBootstrap = new ArrayList<>();
        Map<String, String> projectedWorkers = new LinkedHashMap<>();
        Map<String, String> deferred = new LinkedHashMap<>();
        Map<String, Double> projectedRemainingCapacity = new LinkedHashMap<>();
        Set<String> staffingBootstrapCapabilities = new LinkedHashSet<>();

        String globalBlock = globalBlockReason(state, at);
        for (DurableWorkGraph.Node node : ready) {
            String stepId = node.spec().stepId();
            if (!globalBlock.isBlank()) {
                deferred.put(stepId, globalBlock);
                continue;
            }
            if (selected.size() >= maxParallelism) {
                deferred.put(stepId, "structural-parallelism-ceiling");
                continue;
            }
            if (remainingAttempts - selected.size() <= 0) {
                deferred.put(stepId, "dispatch-attempt-budget-exhausted");
                continue;
            }

            AutonomousExecutionCapability capability = capabilities.get(node.spec().requiredCapability());
            if (capability == null) {
                deferred.put(stepId, "capability-unavailable:" + node.spec().requiredCapability());
                continue;
            }
            if (capability.authorityReference() == null || capability.authorityReference().isBlank()
                    || capability.authorizationReference() == null || capability.authorizationReference().isBlank()) {
                deferred.put(stepId, "authorization-context-unavailable");
                continue;
            }

            AutonomySafetyState.RiskLevel actualRisk = node.spec().consequence() == ExecutionWorkSpec.Consequence.READ_ONLY
                    ? AutonomySafetyState.RiskLevel.LOW : AutonomySafetyState.RiskLevel.HIGH;
            if (actualRisk.ordinal() > state.maxRisk().ordinal()) {
                deferred.put(stepId, "risk-threshold-exceeded:" + actualRisk);
                continue;
            }

            double requiredCapacity = Math.max(0.000001d, capability.requiredCapacity());
            double estimatedCost = requiredCapacity
                    * (node.spec().consequence() == ExecutionWorkSpec.Consequence.READ_ONLY ? 1.0 : 5.0);
            if (projectedCost + estimatedCost > remainingCost + 1e-9) {
                deferred.put(stepId, "budget-threshold-exceeded");
                continue;
            }

            List<WorkforceCoreService.Worker> eligible = core.eligibleWorkers(
                            capability.capabilityRef(), capability.minimumCapabilityLevel(), requiredCapacity, at)
                    .stream().filter(worker -> capability.supportsWorker(worker.workerId(), node.spec())).toList();
            WorkforceCoreService.Worker projectedWorker = null;
            for (WorkforceCoreService.Worker candidate : eligible) {
                double projectedRemaining = projectedRemainingCapacity.computeIfAbsent(
                        candidate.workerId(), core::remainingCapacity);
                if (projectedRemaining + 1e-9 >= requiredCapacity) {
                    projectedWorker = candidate;
                    break;
                }
            }

            if (projectedWorker != null) {
                projectedRemainingCapacity.compute(projectedWorker.workerId(),
                        (ignored, capacity) -> Objects.requireNonNull(capacity) - requiredCapacity);
                projectedWorkers.put(stepId, projectedWorker.workerId());
                selected.add(stepId);
                projectedCost += estimatedCost;
                continue;
            }

            // No admitted capacity yet: permit exactly one bounded dispatch for this capability so
            // the governed allocation boundary can trigger canonical autonomous staffing/AI formation.
            if (eligible.isEmpty() && staffingBootstrapCapabilities.add(capability.capabilityRef())) {
                selected.add(stepId);
                staffingBootstrap.add(stepId);
                projectedCost += estimatedCost;
            } else {
                deferred.put(stepId, "finite-capacity-unavailable");
            }
        }

        AutonomySchedulingDecision decision = new AutonomySchedulingDecision(
                objectiveId + ":graph:" + graphVersion + ":schedule:" + (decisions.size() + 1),
                objectiveId,
                graphVersion,
                ready.stream().map(node -> node.spec().stepId()).toList(),
                selected,
                staffingBootstrap,
                projectedWorkers,
                deferred,
                maxParallelism,
                remainingAttempts,
                remainingCost,
                projectedCost,
                state.deadline(),
                state.maxRisk(),
                at);
        decisions.add(decision);
        persist();
        LOG.info("autonomy_scheduler_decision objective_id={} graph_version={} decision_id={} ready={} selected={} staffing_bootstrap={} deferred={} remaining_attempts={} remaining_cost_units={} selected_cost_units={} deadline={} max_risk={}",
                objectiveId, graphVersion, decision.decisionId(), decision.readyStepIds(), decision.selectedStepIds(),
                decision.staffingBootstrapStepIds(), decision.deferredReasons(), remainingAttempts,
                remainingCost, projectedCost, state.deadline(), state.maxRisk());
        return decision;
    }

    public synchronized List<AutonomySchedulingDecision> decisionsForObjective(String objectiveId) {
        require(objectiveId, "objectiveId");
        return decisions.stream().filter(decision -> decision.objectiveId().equals(objectiveId)).toList();
    }

    public synchronized List<AutonomySchedulingDecision> allDecisions() {
        return List.copyOf(decisions);
    }

    private static String globalBlockReason(AutonomySafetyState state, Instant at) {
        if (state.controlStatus() == AutonomySafetyState.ControlStatus.PAUSED) return "objective-paused";
        if (state.controlStatus() == AutonomySafetyState.ControlStatus.CANCELLED) return "objective-cancelled";
        if (state.authorityRevoked()) return "authority-revoked:" + state.revokedAuthorityReference();
        if (!at.isBefore(state.deadline())) return "deadline-exceeded";
        return "";
    }

    private void persist() {
        store.save(new AutonomySchedulingStateStore.Snapshot(decisions));
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }
}
