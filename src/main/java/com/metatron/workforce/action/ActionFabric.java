package com.metatron.workforce.action;

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
 * Actions are the concrete tool effects a staffed Worker may choose while completing that work.
 * Every invocation is re-authorized against Worker identity, Assignment identity, Authorization and
 * consequence before any tool code is entered.</p>
 */
public final class ActionFabric {
    public enum Consequence { READ_ONLY, MUTATING }

    /** One real tool/effect exposed to a Cognitive Worker. */
    public interface Action {
        String actionRef();
        Consequence consequence();
        Set<String> allowedWorkers();
        Set<String> acceptedAuthorizations();
        ActionObservation invoke(ActionRequest request);
    }

    /** Fully attributed action invocation. */
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
            actionRef = require(actionRef, "actionRef");
            workerId = require(workerId, "workerId");
            assignmentReference = require(assignmentReference, "assignmentReference");
            authorizationReference = require(authorizationReference, "authorizationReference");
            objectiveId = require(objectiveId, "objectiveId");
            workStepId = require(workStepId, "workStepId");
            idempotencyKey = require(idempotencyKey, "idempotencyKey");
            inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        }

        public String actionIdempotencyKey(int cycle) {
            if (cycle < 1) throw new IllegalArgumentException("cycle must be positive");
            return idempotencyKey + ":action:" + actionRef + ":cycle:" + cycle;
        }
    }

    /** Observation returned by the external tool boundary. */
    public record ActionObservation(
            String actionRef,
            boolean success,
            String summary,
            Map<String, String> outputs,
            List<String> evidenceReferences,
            Instant observedAt) {
        public ActionObservation {
            actionRef = require(actionRef, "actionRef");
            summary = require(summary, "summary");
            outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
            evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
            Objects.requireNonNull(observedAt, "observedAt");
        }

        public static ActionObservation success(String actionRef, String summary,
                                                Map<String, String> outputs,
                                                List<String> evidenceReferences) {
            return new ActionObservation(actionRef, true, summary, outputs, evidenceReferences, Instant.now());
        }

        public static ActionObservation failure(String actionRef, String summary,
                                                List<String> evidenceReferences) {
            return new ActionObservation(actionRef, false, summary, Map.of(), evidenceReferences, Instant.now());
        }
    }

    private final Map<String, Action> actions;

    public ActionFabric(Collection<? extends Action> actions) {
        Objects.requireNonNull(actions, "actions");
        Map<String, Action> indexed = new LinkedHashMap<>();
        for (Action action : actions) {
            Objects.requireNonNull(action, "action");
            String ref = require(action.actionRef(), "action.actionRef");
            if (indexed.putIfAbsent(ref, action) != null) {
                throw new IllegalArgumentException("duplicate actionRef: " + ref);
            }
        }
        this.actions = Map.copyOf(indexed);
    }

    public List<String> catalogFor(String workerId, String authorizationReference, boolean mutatingWork) {
        String worker = require(workerId, "workerId");
        String authorization = require(authorizationReference, "authorizationReference");
        List<String> available = new ArrayList<>();
        for (Action action : actions.values()) {
            if (!authorized(action, worker, authorization)) continue;
            if (action.consequence() == Consequence.MUTATING && !mutatingWork) continue;
            available.add(action.actionRef());
        }
        return List.copyOf(available);
    }

    public ActionObservation execute(ActionRequest request) {
        Objects.requireNonNull(request, "request");
        Action action = actions.get(request.actionRef());
        if (action == null) throw new IllegalArgumentException("unknown-action:" + request.actionRef());
        if (!authorized(action, request.workerId(), request.authorizationReference())) {
            throw new SecurityException("action-not-authorized:" + request.actionRef()
                    + ":worker=" + request.workerId());
        }
        if (action.consequence() == Consequence.MUTATING && !request.mutatingWork()) {
            throw new SecurityException("mutating-action-on-read-only-work:" + request.actionRef());
        }
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
        return new ActionObservation(observation.actionRef(), observation.success(), observation.summary(),
                observation.outputs(), evidence, observation.observedAt());
    }

    public Set<String> actionRefs() {
        return new LinkedHashSet<>(actions.keySet());
    }

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
