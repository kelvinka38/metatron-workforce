package com.metatron.workforce.action;

import com.metatron.workforce.execution.governance.ExecutionGate;
import com.metatron.workforce.execution.governance.ExecutionPermit;
import com.metatron.workforce.execution.governance.GovernanceDeniedException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Governed execution plane for Cognitive Workers.
 *
 * <p>An Action is smaller than a Workforce capability. Capabilities describe what a Worker can own;
 * Actions are concrete tool effects. READ_ONLY execution retains the historical identity/authorization
 * checks. MUTATING execution additionally requires a short-lived SoT/plan-bound ExecutionPermit before
 * any tool code is entered.</p>
 */
public final class ActionFabric {
    public enum Consequence { READ_ONLY, MUTATING }

    public interface Action {
        String actionRef();
        Consequence consequence();
        Set<String> allowedWorkers();
        Set<String> acceptedAuthorizations();
        ActionObservation invoke(ActionRequest request);
    }

    public record ActionRequest(
            String actionRef,
            String workerId,
            String assignmentReference,
            String authorizationReference,
            String objectiveId,
            String workStepId,
            String idempotencyKey,
            boolean mutatingWork,
            Map<String, String> inputs) {
        public ActionRequest {
            actionRef = require(actionRef, "actionRef"); workerId = require(workerId, "workerId");
            assignmentReference = require(assignmentReference, "assignmentReference");
            authorizationReference = require(authorizationReference, "authorizationReference");
            objectiveId = require(objectiveId, "objectiveId"); workStepId = require(workStepId, "workStepId");
            idempotencyKey = require(idempotencyKey, "idempotencyKey");
            inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        }

        public String actionIdempotencyKey(int cycle) {
            if (cycle < 1) throw new IllegalArgumentException("cycle must be positive");
            return idempotencyKey + ":action:" + actionRef + ":cycle:" + cycle;
        }
    }

    public record ActionObservation(
            String actionRef, boolean success, String summary, Map<String, String> outputs,
            List<String> evidenceReferences, Instant observedAt) {
        public ActionObservation {
            actionRef = require(actionRef, "actionRef"); summary = require(summary, "summary");
            outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
            evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
            Objects.requireNonNull(observedAt, "observedAt");
        }
        public static ActionObservation success(String actionRef, String summary, Map<String, String> outputs,
                                                List<String> evidenceReferences) {
            return new ActionObservation(actionRef, true, summary, outputs, evidenceReferences, Instant.now());
        }
        public static ActionObservation failure(String actionRef, String summary, List<String> evidenceReferences) {
            return new ActionObservation(actionRef, false, summary, Map.of(), evidenceReferences, Instant.now());
        }
    }

    private final Map<String, Action> actions;
    private final ExecutionGate executionGate;

    /** Compatibility constructor. READ_ONLY remains usable; MUTATING fails closed without a gate/permit. */
    public ActionFabric(Collection<? extends Action> actions) { this(actions, null); }

    public ActionFabric(Collection<? extends Action> actions, ExecutionGate executionGate) {
        Objects.requireNonNull(actions, "actions");
        Map<String, Action> indexed = new LinkedHashMap<>();
        for (Action action : actions) {
            Objects.requireNonNull(action, "action");
            String ref = require(action.actionRef(), "action.actionRef");
            if (indexed.putIfAbsent(ref, action) != null) throw new IllegalArgumentException("duplicate actionRef: " + ref);
        }
        this.actions = Map.copyOf(indexed);
        this.executionGate = executionGate;
    }

    public List<String> catalogFor(String workerId, String authorizationReference, boolean mutatingWork) {
        String worker = require(workerId, "workerId"); String authorization = require(authorizationReference, "authorizationReference");
        List<String> available = new ArrayList<>();
        for (Action action : actions.values()) {
            if (!authorized(action, worker, authorization)) continue;
            if (action.consequence() == Consequence.MUTATING && !mutatingWork) continue;
            available.add(action.actionRef());
        }
        return List.copyOf(available);
    }

    public ActionObservation execute(ActionRequest request) {
        return executeInternal(request, null);
    }

    public ActionObservation execute(ActionRequest request, ExecutionPermit permit) {
        return executeInternal(request, permit);
    }

    private ActionObservation executeInternal(ActionRequest request, ExecutionPermit permit) {
        Objects.requireNonNull(request, "request");
        Action action = actions.get(request.actionRef());
        if (action == null) throw new IllegalArgumentException("unknown-action:" + request.actionRef());
        if (!authorized(action, request.workerId(), request.authorizationReference())) {
            throw new SecurityException("action-not-authorized:" + request.actionRef() + ":worker=" + request.workerId());
        }
        if (action.consequence() == Consequence.MUTATING) {
            if (!request.mutatingWork()) throw new SecurityException("mutating-action-on-read-only-work:" + request.actionRef());
            if (executionGate == null || permit == null) {
                throw new GovernanceDeniedException("EXECUTION_PERMIT_REQUIRED", request.actionRef());
            }
            executionGate.requirePermitMatches(permit, request.objectiveId(), request.workerId(),
                    request.assignmentReference(), request.authorizationReference(), request.workStepId(),
                    request.actionRef(), Instant.now());
        }

        // No real tool code is entered before all applicable checks above pass.
        ActionObservation observation = Objects.requireNonNull(action.invoke(request), "action observation");
        if (!request.actionRef().equals(observation.actionRef())) {
            throw new IllegalStateException("action observation attribution mismatch");
        }
        List<String> evidence = new ArrayList<>(observation.evidenceReferences());
        evidence.add("action-fabric:action=" + request.actionRef()
                + ":worker=" + request.workerId()
                + ":assignment=" + request.assignmentReference()
                + ":authorization=" + request.authorizationReference()
                + ":consequence=" + action.consequence()
                + ":success=" + observation.success());
        if (action.consequence() == Consequence.MUTATING) {
            evidence.add("execution-permit:" + permit.permitId());
            evidence.add("execution-plan:" + permit.planId() + "@" + permit.planVersion());
            evidence.add("execution-authority-digest:" + permit.authorityDigest());
        }
        return new ActionObservation(observation.actionRef(), observation.success(), observation.summary(),
                observation.outputs(), evidence, observation.observedAt());
    }

    public Set<String> actionRefs() { return new LinkedHashSet<>(actions.keySet()); }

    private static boolean authorized(Action action, String workerId, String authorizationReference) {
        Set<String> workers = Objects.requireNonNull(action.allowedWorkers(), "allowedWorkers");
        Set<String> authorizations = Objects.requireNonNull(action.acceptedAuthorizations(), "acceptedAuthorizations");
        return workers.contains(workerId) && authorizations.contains(authorizationReference);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }
}
