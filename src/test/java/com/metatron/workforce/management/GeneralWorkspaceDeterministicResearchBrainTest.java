package com.metatron.workforce.management;

import com.metatron.workforce.action.ActionFabric;
import com.metatron.workforce.action.CognitiveWorkerRuntime;
import com.metatron.workforce.action.GeneralWebResearchAction;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralWorkspaceDeterministicResearchBrainTest {

    @Test
    void explicitGovernedResearchSelectsOnlyWebSearchAndCompletesFromActionObservation() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "general-external-research",
                "Research public evidence for Scatophagus argus grow-out and return a JSON evidence object",
                "Bac Lieu, Vietnam",
                GeneralWorkspaceAutonomousCapability.CAPABILITY,
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("JSON evidence object or explicit nulls"),
                List.of("research-action:research.web.search", "Exact public source URLs"));

        CognitiveWorkerRuntime.Brain brain =
                GeneralWorkspaceAutonomousCapability.deterministicExternalResearchBrain(work);
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                GeneralWorkspaceAutonomousCapability.WORKER_ID,
                "assignment:test",
                GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE,
                "objective:test",
                work,
                "idempotency:test",
                List.of(GeneralWebResearchAction.ACTION_REF),
                List.of(),
                Map.of());

        CognitiveWorkerRuntime.Thought thought = brain.think(context);
        assertEquals(GeneralWebResearchAction.ACTION_REF, thought.actionRef());
        assertTrue(thought.inputs().get("query").contains("Scatophagus argus"));
        assertTrue(thought.inputs().get("query").contains("JSON evidence object or explicit nulls"));
        assertTrue(thought.inputs().get("query").contains("Exact public source URLs"));

        CognitiveWorkerRuntime.Reflection reflection = brain.reflect(
                context,
                ActionFabric.ActionObservation.success(
                        GeneralWebResearchAction.ACTION_REF,
                        "public research complete",
                        Map.of("researchResult", "result"),
                        List.of("https://example.org/source")));

        assertEquals(CognitiveWorkerRuntime.Decision.COMPLETE, reflection.decision());
    }

    @Test
    void failedResearchFailsClosedInsteadOfInventingCompletion() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "general-external-research",
                "Research current external evidence",
                "Vietnam",
                GeneralWorkspaceAutonomousCapability.CAPABILITY,
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("source-backed result"),
                List.of("research-action:research.web.search"));
        CognitiveWorkerRuntime.Brain brain =
                GeneralWorkspaceAutonomousCapability.deterministicExternalResearchBrain(work);
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                GeneralWorkspaceAutonomousCapability.WORKER_ID,
                "assignment:test",
                GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE,
                "objective:test",
                work,
                "idempotency:test",
                List.of(GeneralWebResearchAction.ACTION_REF),
                List.of(),
                Map.of());

        CognitiveWorkerRuntime.Reflection reflection = brain.reflect(
                context,
                ActionFabric.ActionObservation.failure(
                        GeneralWebResearchAction.ACTION_REF,
                        "no attributable source available",
                        List.of()));

        assertEquals(CognitiveWorkerRuntime.Decision.FAILED, reflection.decision());
    }
}
