package com.metatron.workforce.action;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded Cognitive Worker runtime: think -> act -> observe -> reflect.
 *
 * <p>The Brain decides the next Action from durable work context and prior observations. It never
 * receives raw tool clients. Effects can occur only through {@link ActionFabric}, which rechecks the
 * assigned Worker and Authorization on every action. The loop is deliberately bounded so cognition
 * cannot turn into an ungoverned infinite agent loop.</p>
 */
public final class CognitiveWorkerRuntime {
    public static final int DEFAULT_MAX_CYCLES = 32;

    public enum Decision { CONTINUE, COMPLETE, FAILED }

    public interface Brain {
        Thought think(CognitiveContext context);
        Reflection reflect(CognitiveContext context, ActionFabric.ActionObservation observation);
    }

    /** An auditable decision about the next tool action. rationale is a concise decision reason, not hidden model scratchpad. */
    public record Thought(String actionRef, Map<String, String> inputs, String rationale) {
        public Thought {
            actionRef = require(actionRef, "actionRef");
            inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
            rationale = require(rationale, "rationale");
        }
    }

    public record Reflection(Decision decision, String summary) {
        public Reflection {
            Objects.requireNonNull(decision, "decision");
            summary = require(summary, "summary");
        }
        public static Reflection continueWith(String summary) { return new Reflection(Decision.CONTINUE, summary); }
        public static Reflection complete(String summary) { return new Reflection(Decision.COMPLETE, summary); }
        public static Reflection failed(String summary) { return new Reflection(Decision.FAILED, summary); }
    }

    public record Cycle(
            int number,
            Thought thought,
            ActionFabric.ActionObservation observation,
            Reflection reflection) {
        public Cycle {
            if (number < 1) throw new IllegalArgumentException("cycle number must be positive");
            Objects.requireNonNull(thought, "thought");
            Objects.requireNonNull(observation, "observation");
            Objects.requireNonNull(reflection, "reflection");
        }
    }

    public record CognitiveContext(
            String workerId,
            String assignmentReference,
            String authorizationReference,
            String objectiveId,
            ExecutionWorkSpec workSpec,
            String idempotencyKey,
            List<String> availableActions,
            List<Cycle> history,
            Map<String, String> memory) {
        public CognitiveContext {
            workerId = require(workerId, "workerId");
            assignmentReference = require(assignmentReference, "assignmentReference");
            authorizationReference = require(authorizationReference, "authorizationReference");
            objectiveId = require(objectiveId, "objectiveId");
            Objects.requireNonNull(workSpec, "workSpec");
            idempotencyKey = require(idempotencyKey, "idempotencyKey");
            availableActions = availableActions == null ? List.of() : List.copyOf(availableActions);
            history = history == null ? List.of() : List.copyOf(history);
            memory = memory == null ? Map.of() : Map.copyOf(memory);
        }

        public int nextCycle() { return history.size() + 1; }
        public ActionFabric.ActionObservation lastObservation() {
            return history.isEmpty() ? null : history.getLast().observation();
        }
    }

    public record Outcome(
            boolean success,
            List<Cycle> cycles,
            Map<String, String> memory,
            List<String> evidenceReferences,
            String summary,
            Instant completedAt) {
        public Outcome {
            cycles = cycles == null ? List.of() : List.copyOf(cycles);
            memory = memory == null ? Map.of() : Map.copyOf(memory);
            evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
            summary = require(summary, "summary");
            Objects.requireNonNull(completedAt, "completedAt");
        }
    }

    private final ActionFabric fabric;
    private final ActionJournal journal;
    private final int maxCycles;

    public CognitiveWorkerRuntime(ActionFabric fabric) {
        this(fabric, ActionJournal.noop(), DEFAULT_MAX_CYCLES);
    }

    public CognitiveWorkerRuntime(ActionFabric fabric, ActionJournal journal, int maxCycles) {
        this.fabric = Objects.requireNonNull(fabric, "fabric");
        this.journal = Objects.requireNonNull(journal, "journal");
        if (maxCycles < 1 || maxCycles > 512) throw new IllegalArgumentException("maxCycles must be between 1 and 512");
        this.maxCycles = maxCycles;
    }

    public Outcome execute(String workerId,
                           String assignmentReference,
                           String authorizationReference,
                           String objectiveId,
                           ExecutionWorkSpec workSpec,
                           String idempotencyKey,
                           Brain brain) {
        Objects.requireNonNull(brain, "brain");
        boolean mutating = workSpec.consequence() == ExecutionWorkSpec.Consequence.MUTATING;
        List<String> catalog = fabric.catalogFor(workerId, authorizationReference, mutating);
        if (catalog.isEmpty()) throw new IllegalStateException("cognitive-worker-action-catalog-empty:" + workerId);

        List<Cycle> history = new ArrayList<>();
        Map<String, String> memory = new LinkedHashMap<>();
        List<String> evidence = new ArrayList<>();
        String terminalSummary = "cognitive worker exhausted without terminal reflection";
        boolean success = false;

        for (int cycleNumber = 1; cycleNumber <= maxCycles; cycleNumber++) {
            CognitiveContext before = new CognitiveContext(workerId, assignmentReference, authorizationReference,
                    objectiveId, workSpec, idempotencyKey, catalog, history, memory);
            Thought thought = Objects.requireNonNull(brain.think(before), "brain thought");
            if (!catalog.contains(thought.actionRef())) {
                throw new SecurityException("brain-selected-action-outside-catalog:" + thought.actionRef());
            }

            ActionFabric.ActionRequest request = new ActionFabric.ActionRequest(
                    thought.actionRef(), workerId, assignmentReference, authorizationReference,
                    objectiveId, workSpec.stepId(), idempotencyKey, mutating, thought.inputs());
            ActionFabric.ActionObservation observation;
            try {
                observation = fabric.execute(request);
            } catch (RuntimeException failure) {
                observation = ActionFabric.ActionObservation.failure(thought.actionRef(),
                        "action threw " + failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()),
                        List.of("action-exception:" + failure.getClass().getSimpleName()));
            }

            for (Map.Entry<String, String> output : observation.outputs().entrySet()) {
                if (output.getValue() != null) memory.put(output.getKey(), output.getValue());
            }
            CognitiveContext afterAction = new CognitiveContext(workerId, assignmentReference, authorizationReference,
                    objectiveId, workSpec, idempotencyKey, catalog, history, memory);
            Reflection reflection = Objects.requireNonNull(brain.reflect(afterAction, observation), "brain reflection");
            Cycle cycle = new Cycle(cycleNumber, thought, observation, reflection);
            history.add(cycle);
            evidence.addAll(observation.evidenceReferences());
            evidence.add("cognitive-cycle:" + cycleNumber
                    + ":action=" + thought.actionRef()
                    + ":observation=" + (observation.success() ? "SUCCESS" : "FAILED")
                    + ":reflection=" + reflection.decision());
            journal.append(objectiveId, workSpec.stepId(), workerId, assignmentReference,
                    authorizationReference, idempotencyKey, cycle);

            terminalSummary = reflection.summary();
            if (reflection.decision() == Decision.COMPLETE) {
                if (!observation.success()) {
                    throw new IllegalStateException("brain-cannot-complete-after-failed-observation");
                }
                success = true;
                break;
            }
            if (reflection.decision() == Decision.FAILED) {
                success = false;
                break;
            }
        }

        if (!success && history.size() >= maxCycles
                && history.getLast().reflection().decision() == Decision.CONTINUE) {
            terminalSummary = "cognitive worker cycle budget exhausted after " + maxCycles + " actions";
            evidence.add("cognitive-cycle-budget-exhausted:" + maxCycles);
        }
        evidence.add("cognitive-worker:worker=" + workerId
                + ":assignment=" + assignmentReference
                + ":actions=" + history.size()
                + ":success=" + success);
        return new Outcome(success, history, memory, evidence, terminalSummary, Instant.now());
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }
}
