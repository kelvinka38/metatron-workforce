package com.metatron.workforce.action;

import com.metatron.workforce.execution.governance.ExecutionGate;
import com.metatron.workforce.execution.governance.ExecutionIntent;
import com.metatron.workforce.execution.governance.ExecutionPermit;
import com.metatron.workforce.execution.governance.GovernanceDeniedException;
import com.metatron.workforce.execution.governance.GovernanceExecutionContext;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded Cognitive Worker runtime: think -> authorize -> act -> observe -> reflect.
 *
 * <p>The Brain may choose among exposed Actions, but it cannot issue execution authority. Every
 * MUTATING Action is converted into a trusted ExecutionIntent and must receive an ExecutionPermit
 * from the institution-owned ExecutionGate before ActionFabric can enter tool code. Governance
 * denial is not converted into a creative retry signal.</p>
 */
public final class CognitiveWorkerRuntime {
    public static final int DEFAULT_MAX_CYCLES = 32;

    public enum Decision { CONTINUE, COMPLETE, FAILED }

    public interface Brain {
        Thought think(CognitiveContext context);
        Reflection reflect(CognitiveContext context, ActionFabric.ActionObservation observation);

        default boolean blocksCompletionForUnresolvedFailure(CognitiveContext context, String actionRef) {
            return true;
        }
    }

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

    public record Cycle(int number, Thought thought, ActionFabric.ActionObservation observation, Reflection reflection) {
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
    private final ExecutionGate executionGate;

    public CognitiveWorkerRuntime(ActionFabric fabric) {
        this(fabric, ActionJournal.noop(), DEFAULT_MAX_CYCLES, null);
    }

    public CognitiveWorkerRuntime(ActionFabric fabric, ActionJournal journal, int maxCycles) {
        this(fabric, journal, maxCycles, null);
    }

    public CognitiveWorkerRuntime(ActionFabric fabric, ActionJournal journal, int maxCycles, ExecutionGate executionGate) {
        this.fabric = Objects.requireNonNull(fabric, "fabric");
        this.journal = Objects.requireNonNull(journal, "journal");
        if (maxCycles < 1 || maxCycles > 512) throw new IllegalArgumentException("maxCycles must be between 1 and 512");
        this.maxCycles = maxCycles;
        this.executionGate = executionGate;
    }

    public Outcome execute(String workerId,
                           String assignmentReference,
                           String authorizationReference,
                           String objectiveId,
                           ExecutionWorkSpec workSpec,
                           String idempotencyKey,
                           Brain brain) {
        return execute(workerId, assignmentReference, authorizationReference, objectiveId, workSpec,
                idempotencyKey, null, brain);
    }

    public Outcome execute(String workerId,
                           String assignmentReference,
                           String authorizationReference,
                           String objectiveId,
                           ExecutionWorkSpec workSpec,
                           String idempotencyKey,
                           GovernanceExecutionContext governanceContext,
                           Brain brain) {
        Objects.requireNonNull(brain, "brain");
        boolean mutating = workSpec.consequence() == ExecutionWorkSpec.Consequence.MUTATING;
        if (mutating && (governanceContext == null || executionGate == null)) {
            throw new GovernanceDeniedException("SOT_DISCOVERY_REQUIRED",
                    "mutating Cognitive Worker execution requires governance context and ExecutionGate");
        }
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
            Cycle previous = history.isEmpty() ? null : history.getLast();
            boolean immediateRedundantMutation = previous != null
                    && previous.observation().success()
                    && previous.thought().actionRef().equals(thought.actionRef())
                    && previous.thought().inputs().equals(thought.inputs())
                    && fabric.consequenceOf(thought.actionRef()) == ActionFabric.Consequence.MUTATING;
            if (immediateRedundantMutation) {
                observation = ActionFabric.ActionObservation.failure(
                        thought.actionRef(),
                        "redundant identical successful mutation blocked; choose an action that changes or inspects state",
                        List.of("action-redundant-no-state-change:" + thought.actionRef()));
            } else try {
                ExecutionPermit permit = authorizeMutationIfRequired(request, governanceContext, thought.inputs());
                observation = permit == null ? fabric.execute(request) : fabric.execute(request, permit);
            } catch (GovernanceDeniedException denied) {
                evidence.add("cognitive-governance-denial:" + denied.code());
                throw denied;
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
            if (reflection.decision() == Decision.COMPLETE) {
                if (!observation.success()) {
                    throw new IllegalStateException("brain-cannot-complete-after-failed-observation");
                }
                Set<String> unresolvedFailures = unresolvedFailedActions(history, observation).stream()
                        .filter(actionRef -> brain.blocksCompletionForUnresolvedFailure(afterAction, actionRef))
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
                if (!unresolvedFailures.isEmpty()) {
                    String unresolved = String.join(",", unresolvedFailures);
                    evidence.add("cognitive-completion-rejected:unresolved-failed-actions=" + unresolved);
                    reflection = Reflection.continueWith(
                            "completion rejected until failed actions are successfully retried: " + unresolved);
                } else if (mutating) {
                    evidence.add("cognitive-completion-candidate:step=" + workSpec.stepId());
                }
            }

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

    private ExecutionPermit authorizeMutationIfRequired(ActionFabric.ActionRequest request,
                                                         GovernanceExecutionContext context,
                                                         Map<String, String> inputs) {
        ActionFabric.Consequence consequence = fabric.consequenceOf(request.actionRef());
        if (consequence == ActionFabric.Consequence.READ_ONLY) return null;
        if (context == null || executionGate == null) {
            throw new GovernanceDeniedException("EXECUTION_PERMIT_REQUIRED", request.actionRef());
        }
        ExecutionIntent intent = new ExecutionIntent(
                request.objectiveId(), context.attemptId(), context.fencingToken(), request.workerId(),
                request.assignmentReference(), request.authorizationReference(), context.planId(), context.planVersion(),
                request.workStepId(), request.actionRef(), consequence, context.targetScope(),
                context.authoritySnapshotId(), context.derivationReceiptId(), inputs, Instant.now());
        return executionGate.authorize(intent);
    }

    static Set<String> unresolvedFailedActions(List<Cycle> history, ActionFabric.ActionObservation latest) {
        LinkedHashSet<String> unresolved = new LinkedHashSet<>();
        if (history != null) {
            for (Cycle cycle : history) applyObservationState(unresolved, cycle.observation());
        }
        if (latest != null) applyObservationState(unresolved, latest);
        return Set.copyOf(unresolved);
    }

    private static void applyObservationState(Set<String> unresolved, ActionFabric.ActionObservation observation) {
        if (observation.success()) unresolved.remove(observation.actionRef());
        else unresolved.add(observation.actionRef());
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }
}
