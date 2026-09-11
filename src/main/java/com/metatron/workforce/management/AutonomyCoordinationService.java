package com.metatron.workforce.management;

import com.metatron.workforce.execution.governance.CompletionCandidate;
import com.metatron.workforce.execution.governance.CompletionDecision;
import com.metatron.workforce.execution.governance.CompletionGate;
import com.metatron.workforce.execution.governance.ExecutionPlanBinding;
import com.metatron.workforce.execution.governance.GovernanceDeniedException;
import com.metatron.workforce.execution.governance.GovernanceStateStore;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.observation.ObservationClosureService;
import com.metatron.workforce.observation.ObservationReport;
import com.metatron.workforce.observation.ObservationRequirement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable P3/P4 coordination: inbox dedupe, versioned DAG, fenced dispatch lifecycle,
 * restart reconciliation and dead-letter handling. Workforce Objective remains authoritative.
 * Consequential graph completion is additionally gated by current SoT/plan and independent Observation.
 */
public final class AutonomyCoordinationService {
    private final Map<String, Integer> activeGraphVersions = new LinkedHashMap<>();
    private final Map<String, DurableWorkGraph> graphs = new LinkedHashMap<>();
    private final Map<String, DurableDispatch> dispatches = new LinkedHashMap<>();
    private final Map<String, AutonomyCoordinationStateStore.InboxMessage> inbox = new LinkedHashMap<>();
    private final List<AutonomyCoordinationStateStore.DeadLetter> deadLetters = new ArrayList<>();
    private final AutonomyCoordinationStateStore store;
    private final CompletionGate completionGate;
    private final GovernanceStateStore governance;
    private final ObservationClosureService observation;

    public AutonomyCoordinationService() { this(new InMemoryAutonomyCoordinationStateStore(), null, null, null); }

    public AutonomyCoordinationService(AutonomyCoordinationStateStore store) {
        this(store, null, null, null);
    }

    /** Production composition: consequential completion cannot bypass CompletionGate. */
    public AutonomyCoordinationService(AutonomyCoordinationStateStore store,
                                       CompletionGate completionGate,
                                       GovernanceStateStore governance,
                                       ObservationClosureService observation) {
        this.store = Objects.requireNonNull(store, "store");
        this.completionGate = completionGate;
        this.governance = governance;
        this.observation = observation;
        var snapshot = store.load();
        activeGraphVersions.putAll(snapshot.activeGraphVersions());
        graphs.putAll(snapshot.graphs());
        dispatches.putAll(snapshot.dispatches());
        inbox.putAll(snapshot.inbox());
        deadLetters.addAll(snapshot.deadLetters());
    }

    public synchronized boolean acceptInbox(String messageId, String idempotencyKey, String correlationId,
                                             String causationId, int schemaVersion, String payload, Instant at) {
        require(messageId, "messageId"); require(idempotencyKey, "idempotencyKey");
        require(correlationId, "correlationId"); require(causationId, "causationId");
        require(payload, "payload"); Objects.requireNonNull(at, "at");
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        var existing = inbox.get(messageId);
        if (existing != null) {
            if (!existing.idempotencyKey().equals(idempotencyKey))
                throw new IllegalStateException("message id replay conflicts with idempotency key");
            return false;
        }
        boolean duplicateEffect = inbox.values().stream().anyMatch(m -> m.idempotencyKey().equals(idempotencyKey));
        if (duplicateEffect) return false;
        inbox.put(messageId, new AutonomyCoordinationStateStore.InboxMessage(
                messageId, idempotencyKey, correlationId, causationId, schemaVersion, payload, at));
        persist();
        return true;
    }

    public synchronized DurableWorkGraph ensureGraph(String objectiveId, List<ExecutionWorkSpec> plan, Instant at) {
        require(objectiveId, "objectiveId"); Objects.requireNonNull(plan, "plan"); Objects.requireNonNull(at, "at");
        validatePlan(plan);
        Integer active = activeGraphVersions.get(objectiveId);
        if (active != null) {
            DurableWorkGraph current = graph(objectiveId, active);
            boolean failedPlan = current.nodes().values().stream()
                    .anyMatch(node -> node.status() == DurableWorkGraph.NodeStatus.FAILED);
            if (samePlan(current, plan)
                    && current.status() == DurableWorkGraph.Status.ACTIVE
                    && !failedPlan) return current;
            if (current.status() == DurableWorkGraph.Status.ACTIVE) {
                graphs.put(key(objectiveId, active), new DurableWorkGraph(current.objectiveId(), current.graphVersion(),
                        DurableWorkGraph.Status.SUPERSEDED, current.nodes(), current.createdAt(), at));
            }
        }
        int version = active == null ? 1 : active + 1;
        Map<String, DurableWorkGraph.Node> nodes = new LinkedHashMap<>();
        for (ExecutionWorkSpec step : plan) {
            nodes.put(step.stepId(), new DurableWorkGraph.Node(step, DurableWorkGraph.NodeStatus.PENDING,
                    0, "", List.of(), "", at));
        }
        DurableWorkGraph created = new DurableWorkGraph(objectiveId, version,
                DurableWorkGraph.Status.ACTIVE, nodes, at, at);
        graphs.put(key(objectiveId, version), created);
        activeGraphVersions.put(objectiveId, version);
        persist();
        return created;
    }

    public synchronized Optional<DurableWorkGraph> activeGraph(String objectiveId) {
        Integer version = activeGraphVersions.get(objectiveId);
        return version == null ? Optional.empty() : Optional.of(graph(objectiveId, version));
    }

    public synchronized List<DurableWorkGraph.Node> readyNodes(String objectiveId, int graphVersion) {
        DurableWorkGraph graph = requireActive(objectiveId, graphVersion);
        return graph.nodes().values().stream()
                .filter(node -> node.status() == DurableWorkGraph.NodeStatus.PENDING)
                .filter(node -> node.spec().dependsOn().stream().allMatch(dep ->
                        graph.nodes().containsKey(dep)
                                && graph.nodes().get(dep).status() == DurableWorkGraph.NodeStatus.SUCCEEDED))
                .toList();
    }

    public synchronized List<String> reconcileInterrupted(String objectiveId, int graphVersion, Instant at) {
        DurableWorkGraph graph = requireActive(objectiveId, graphVersion);
        Map<String, DurableWorkGraph.Node> nodes = new LinkedHashMap<>(graph.nodes());
        List<String> unsafe = new ArrayList<>();
        boolean changed = false;
        for (var entry : graph.nodes().entrySet()) {
            DurableWorkGraph.Node node = entry.getValue();
            if (node.status() != DurableWorkGraph.NodeStatus.DISPATCHED) continue;
            DurableDispatch dispatch = dispatches.get(node.dispatchId());
            if (dispatch != null && dispatch.status() == DurableDispatch.Status.SUCCEEDED) {
                nodes.put(entry.getKey(), new DurableWorkGraph.Node(node.spec(), DurableWorkGraph.NodeStatus.SUCCEEDED,
                        node.attempt(), node.dispatchId(), dispatch.evidenceReferences(), "", at));
                changed = true;
                continue;
            }
            if (node.spec().consequence() == ExecutionWorkSpec.Consequence.READ_ONLY) {
                if (dispatch != null && dispatch.status() == DurableDispatch.Status.STARTED) {
                    dispatches.put(dispatch.dispatchId(), copyDispatch(dispatch, DurableDispatch.Status.FAILED,
                            dispatch.evidenceReferences(), "interrupted-read-only-retry", at));
                }
                nodes.put(entry.getKey(), new DurableWorkGraph.Node(node.spec(), DurableWorkGraph.NodeStatus.PENDING,
                        node.attempt(), "", node.evidenceReferences(), "interrupted-read-only-retry", at));
            } else {
                String reason = "unknown-mutating-effect-requires-reconciliation";
                if (dispatch != null && dispatch.status() == DurableDispatch.Status.STARTED) {
                    dispatches.put(dispatch.dispatchId(), copyDispatch(dispatch, DurableDispatch.Status.DEAD_LETTERED,
                            dispatch.evidenceReferences(), reason, at));
                }
                nodes.put(entry.getKey(), new DurableWorkGraph.Node(node.spec(), DurableWorkGraph.NodeStatus.FAILED,
                        node.attempt(), node.dispatchId(), node.evidenceReferences(), reason, at));
                deadLetters.add(new AutonomyCoordinationStateStore.DeadLetter(
                        node.dispatchId(), objectiveId, node.spec().stepId(), reason, at));
                unsafe.add(node.spec().stepId());
            }
            changed = true;
        }
        if (changed) {
            graphs.put(key(objectiveId, graphVersion), new DurableWorkGraph(objectiveId, graphVersion,
                    graph.status(), nodes, graph.createdAt(), at));
            persist();
        }
        return List.copyOf(unsafe);
    }

    public synchronized DurableDispatch beginDispatch(String objectiveId, int graphVersion, String stepId, Instant at) {
        DurableWorkGraph graph = requireActive(objectiveId, graphVersion);
        DurableWorkGraph.Node node = requireNode(graph, stepId);
        if (node.status() != DurableWorkGraph.NodeStatus.PENDING)
            throw new IllegalStateException("node is not pending: " + stepId);
        if (!node.spec().dependsOn().stream().allMatch(dep -> graph.nodes().get(dep).status() == DurableWorkGraph.NodeStatus.SUCCEEDED))
            throw new IllegalStateException("node dependencies not complete: " + stepId);
        int attempt = node.attempt() + 1;
        String dispatchId = objectiveId + ":graph:" + graphVersion + ":step:" + stepId + ":attempt:" + attempt;
        String idempotencyKey = objectiveId + ":graph:" + graphVersion + ":step:" + stepId;
        DurableDispatch dispatch = new DurableDispatch(dispatchId, objectiveId, graphVersion, stepId,
                idempotencyKey, DurableDispatch.Status.STARTED, attempt, List.of(), "", at, at);
        dispatches.put(dispatchId, dispatch);
        Map<String, DurableWorkGraph.Node> nodes = new LinkedHashMap<>(graph.nodes());
        nodes.put(stepId, new DurableWorkGraph.Node(node.spec(), DurableWorkGraph.NodeStatus.DISPATCHED,
                attempt, dispatchId, node.evidenceReferences(), "", at));
        graphs.put(key(objectiveId, graphVersion), new DurableWorkGraph(objectiveId, graphVersion,
                graph.status(), nodes, graph.createdAt(), at));
        persist();
        return dispatch;
    }

    public synchronized DurableWorkGraph.Node completeDispatch(String dispatchId, List<String> evidence, Instant at) {
        DurableDispatch dispatch = requireDispatch(dispatchId);
        if (dispatch.status() == DurableDispatch.Status.SUCCEEDED) {
            return requireNode(graph(dispatch.objectiveId(), dispatch.graphVersion()), dispatch.stepId());
        }
        if (dispatch.status() != DurableDispatch.Status.STARTED) throw new IllegalStateException("dispatch not active");
        DurableWorkGraph graph = requireActive(dispatch.objectiveId(), dispatch.graphVersion());
        DurableWorkGraph.Node node = requireNode(graph, dispatch.stepId());
        if (!node.dispatchId().equals(dispatchId)) throw new IllegalStateException("stale dispatch fenced");
        List<String> refs = List.copyOf(evidence == null ? List.of() : evidence);
        dispatches.put(dispatchId, copyDispatch(dispatch, DurableDispatch.Status.SUCCEEDED, refs, "", at));
        Map<String, DurableWorkGraph.Node> nodes = new LinkedHashMap<>(graph.nodes());
        DurableWorkGraph.Node succeeded = new DurableWorkGraph.Node(node.spec(), DurableWorkGraph.NodeStatus.SUCCEEDED,
                node.attempt(), dispatchId, refs, "", at);
        nodes.put(node.spec().stepId(), succeeded);
        graphs.put(key(graph.objectiveId(), graph.graphVersion()), new DurableWorkGraph(graph.objectiveId(),
                graph.graphVersion(), graph.status(), nodes, graph.createdAt(), at));
        persist();
        return succeeded;
    }

    public synchronized void failDispatch(String dispatchId, String reason, Instant at) {
        require(reason, "reason");
        DurableDispatch dispatch = requireDispatch(dispatchId);
        if (dispatch.status() != DurableDispatch.Status.STARTED) return;
        DurableWorkGraph graph = requireActive(dispatch.objectiveId(), dispatch.graphVersion());
        DurableWorkGraph.Node node = requireNode(graph, dispatch.stepId());
        if (!node.dispatchId().equals(dispatchId)) throw new IllegalStateException("stale dispatch fenced");
        dispatches.put(dispatchId, copyDispatch(dispatch, DurableDispatch.Status.FAILED, List.of(), reason, at));
        Map<String, DurableWorkGraph.Node> nodes = new LinkedHashMap<>(graph.nodes());
        nodes.put(node.spec().stepId(), new DurableWorkGraph.Node(node.spec(), DurableWorkGraph.NodeStatus.FAILED,
                node.attempt(), dispatchId, node.evidenceReferences(), reason, at));
        graphs.put(key(graph.objectiveId(), graph.graphVersion()), new DurableWorkGraph(graph.objectiveId(),
                graph.graphVersion(), graph.status(), nodes, graph.createdAt(), at));
        persist();
    }

    public synchronized DurableWorkGraph completeGraph(String objectiveId, int graphVersion, Instant at) {
        DurableWorkGraph graph = requireActive(objectiveId, graphVersion);
        if (graph.nodes().values().stream().anyMatch(node -> node.status() != DurableWorkGraph.NodeStatus.SUCCEEDED))
            throw new IllegalStateException("graph cannot complete with non-success nodes");
        if (graph.nodes().values().stream().anyMatch(node ->
                node.spec().consequence() == ExecutionWorkSpec.Consequence.MUTATING)) {
            requireGovernedCompletion(graph, at);
        }
        DurableWorkGraph completed = new DurableWorkGraph(objectiveId, graphVersion,
                DurableWorkGraph.Status.COMPLETED, graph.nodes(), graph.createdAt(), at);
        graphs.put(key(objectiveId, graphVersion), completed);
        persist();
        return completed;
    }

    private void requireGovernedCompletion(DurableWorkGraph graph, Instant at) {
        if (completionGate == null || governance == null || observation == null) {
            throw new GovernanceDeniedException("COMPLETION_GATE_REQUIRED",
                    "mutating graph completion requires production SoT/Observation governance");
        }
        if (observation.verdict(graph.objectiveId()) != ObservationClosureService.Verdict.PASSED) {
            throw new GovernanceDeniedException("EVIDENCE_INSUFFICIENT",
                    "independent Observation has not passed for objective " + graph.objectiveId());
        }
        List<String> verified = new ArrayList<>(observation.verifiedEvidenceReferences(graph.objectiveId()));
        graph.nodes().values().forEach(node -> node.evidenceReferences().stream()
                .filter(ref -> !verified.contains(ref)).forEach(verified::add));
        List<String> completedSteps = graph.nodes().values().stream()
                .filter(node -> node.status() == DurableWorkGraph.NodeStatus.SUCCEEDED)
                .map(node -> node.spec().stepId()).toList();
        Map<String, ObservationRequirement> requirements = new LinkedHashMap<>();
        observation.requirements(graph.objectiveId()).forEach(r -> requirements.put(r.requirementId(), r));
        Map<String, ObservationReport> reports = new LinkedHashMap<>();
        observation.reports(graph.objectiveId()).forEach(r -> reports.put(r.requirementId(), r));

        for (DurableWorkGraph.Node node : graph.nodes().values()) {
            if (node.spec().consequence() != ExecutionWorkSpec.Consequence.MUTATING) continue;
            ExecutionPlanBinding plan = governance.approvedPlanForStep(graph.objectiveId(), node.spec().stepId())
                    .orElseThrow(() -> new GovernanceDeniedException("PLAN_NOT_APPROVED",
                            "no active approved plan at completion for step " + node.spec().stepId()));
            Map<String, String> acceptance = new LinkedHashMap<>();
            Map<String, String> evidence = new LinkedHashMap<>();
            for (ObservationRequirement requirement : requirements.values()) {
                if (!requirement.stepId().equals(node.spec().stepId())) continue;
                ObservationReport report = reports.get(requirement.requirementId());
                if (report == null || report.criterionResult() != ObservationReport.CriterionResult.PASS
                        || report.quality() == ObservationReport.Quality.INSUFFICIENT) continue;
                acceptance.put(requirement.criterion(), "observation-report:" + report.reportId());
                for (String requiredEvidence : requirement.evidenceRequirements()) {
                    evidence.putIfAbsent(requiredEvidence, "observation-report:" + report.reportId());
                }
            }
            CompletionCandidate candidate = new CompletionCandidate(
                    graph.objectiveId(), node.spec().stepId(), plan.planId(), plan.version(), plan.authoritySnapshotId(),
                    verified, completedSteps, List.of(), acceptance, evidence, true,
                    artifact(verified, "source-sha:", "HIGHWAY_SOURCE_SHA="),
                    artifact(verified, "tested-sha:", "HIGHWAY_WORKFORCE_BUILD_SHA="),
                    artifact(verified, "approved-sha:"),
                    artifact(verified, "deployed-sha:", "DEPLOYED_SHA="),
                    artifact(verified, "observed-sha:", "OBSERVED_SHA="), at);
            CompletionDecision decision = completionGate.decide(candidate);
            if (decision.verdict() != CompletionDecision.Verdict.ALLOW) {
                throw new GovernanceDeniedException("COMPLETION_CONFORMANCE_FAILED",
                        "step=" + node.spec().stepId() + ";codes=" + decision.codes());
            }
        }
    }

    private static String artifact(List<String> refs, String... prefixes) {
        for (String ref : refs) {
            if (ref == null) continue;
            for (String prefix : prefixes) {
                int index = ref.indexOf(prefix);
                if (index < 0) continue;
                String value = ref.substring(index + prefix.length()).trim();
                int separator = value.indexOf(':');
                if (separator > 0 && value.substring(0, separator).matches("[0-9a-fA-F]{40}")) {
                    value = value.substring(0, separator);
                }
                if (value.matches("[0-9a-fA-F]{40}")) return value.toLowerCase(java.util.Locale.ROOT);
            }
        }
        return "";
    }

    public synchronized List<DurableDispatch> dispatches() { return List.copyOf(dispatches.values()); }
    public synchronized List<AutonomyCoordinationStateStore.DeadLetter> deadLetters() { return List.copyOf(deadLetters); }
    public synchronized List<DurableWorkGraph> graphHistory(String objectiveId) {
        return graphs.values().stream().filter(g -> g.objectiveId().equals(objectiveId))
                .sorted(java.util.Comparator.comparingInt(DurableWorkGraph::graphVersion)).toList();
    }

    private DurableWorkGraph requireActive(String objectiveId, int version) {
        Integer active = activeGraphVersions.get(objectiveId);
        if (active == null || active != version) throw new IllegalStateException("stale graph version fenced");
        DurableWorkGraph graph = graph(objectiveId, version);
        if (graph.status() != DurableWorkGraph.Status.ACTIVE) throw new IllegalStateException("graph not active");
        return graph;
    }
    private DurableWorkGraph graph(String objectiveId, int version) {
        DurableWorkGraph graph = graphs.get(key(objectiveId, version));
        if (graph == null) throw new IllegalArgumentException("graph not found");
        return graph;
    }
    private DurableDispatch requireDispatch(String id) {
        DurableDispatch dispatch = dispatches.get(id);
        if (dispatch == null) throw new IllegalArgumentException("dispatch not found: " + id);
        return dispatch;
    }
    private static DurableWorkGraph.Node requireNode(DurableWorkGraph graph, String stepId) {
        DurableWorkGraph.Node node = graph.nodes().get(stepId);
        if (node == null) throw new IllegalArgumentException("graph node not found: " + stepId);
        return node;
    }
    private static boolean samePlan(DurableWorkGraph graph, List<ExecutionWorkSpec> plan) {
        if (graph.nodes().size() != plan.size()) return false;
        for (ExecutionWorkSpec step : plan) {
            DurableWorkGraph.Node node = graph.nodes().get(step.stepId());
            if (node == null || !node.spec().equals(step)) return false;
        }
        return true;
    }
    private static void validatePlan(List<ExecutionWorkSpec> plan) {
        if (plan.isEmpty()) throw new IllegalArgumentException("plan must not be empty");
        Map<String, ExecutionWorkSpec> byId = new LinkedHashMap<>();
        for (ExecutionWorkSpec step : plan) {
            if (byId.putIfAbsent(step.stepId(), step) != null) throw new IllegalArgumentException("duplicate step id");
        }
        for (ExecutionWorkSpec step : plan) for (String dep : step.dependsOn())
            if (!byId.containsKey(dep)) throw new IllegalArgumentException("unknown dependency: " + dep);
        for (String id : byId.keySet()) detectCycle(id, byId, new java.util.HashSet<>(), new java.util.HashSet<>());
    }
    private static void detectCycle(String id, Map<String, ExecutionWorkSpec> byId, java.util.Set<String> visiting,
                                    java.util.Set<String> visited) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalArgumentException("cyclic Work Graph");
        for (String dep : byId.get(id).dependsOn()) detectCycle(dep, byId, visiting, visited);
        visiting.remove(id); visited.add(id);
    }
    private static DurableDispatch copyDispatch(DurableDispatch d, DurableDispatch.Status status,
                                                List<String> evidence, String failure, Instant at) {
        return new DurableDispatch(d.dispatchId(), d.objectiveId(), d.graphVersion(), d.stepId(), d.idempotencyKey(),
                status, d.attempt(), evidence, failure, d.createdAt(), at);
    }
    private void persist() {
        store.save(new AutonomyCoordinationStateStore.Snapshot(activeGraphVersions, graphs, dispatches, inbox, deadLetters));
    }
    private static String key(String objectiveId, int version) { return objectiveId + "#" + version; }
    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }
}
