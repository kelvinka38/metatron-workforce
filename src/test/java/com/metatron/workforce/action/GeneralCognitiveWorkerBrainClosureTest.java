package com.metatron.workforce.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GeneralCognitiveWorkerBrainClosureTest {
    @Test
    void providerBackedBrainUsesActualRuntimeCatalogAndCanReachCompleteReflection() {
        LlmProviderClient client = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                if (request.systemContext().contains("action-selection")) {
                    assertTrue(request.userInput().contains("workspace.file.write"));
                    assertTrue(request.userInput().contains("repair defect"));
                    return new LlmResponse(provider(), request.model(),
                            "{\"actionRef\":\"workspace.file.write\",\"inputs\":{\"path\":\"result.txt\",\"content\":\"fixed\"},\"rationale\":\"write required work product\"}",
                            "req-think");
                }
                assertTrue(request.userInput().contains("workspace file written"));
                return new LlmResponse(provider(), request.model(),
                        "{\"decision\":\"COMPLETE\",\"summary\":\"required work product is now observed\"}",
                        "req-reflect");
            }
        };
        GeneralCognitiveWorkerBrain brain = new GeneralCognitiveWorkerBrain(
                new LlmProviderRouter(List.of(client)), LlmProvider.GOOGLE, "gemini-test", new ObjectMapper());
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "step-1", "repair defect", "workspace", "execution.general.workspace",
                List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("result exists"), List.of("workspace-state"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker-1", "assignment-1", "auth-1", "objective-1", work, "idem-1",
                List.of("workspace.file.read", "workspace.file.write"), List.of(), Map.of());

        CognitiveWorkerRuntime.Thought thought = brain.think(context);
        assertEquals("workspace.file.write", thought.actionRef());
        assertEquals("result.txt", thought.inputs().get("path"));

        ActionFabric.ActionObservation observation = new ActionFabric.ActionObservation(
                "workspace.file.write", true, "workspace file written",
                Map.of("path", "result.txt"), List.of("objective-workspace:evidence"), Instant.now());
        CognitiveWorkerRuntime.Reflection reflection = brain.reflect(context, observation);
        assertEquals(CognitiveWorkerRuntime.Decision.COMPLETE, reflection.decision());
        assertEquals(2, brain.evidenceReferences().size());
        assertTrue(brain.evidenceReferences().stream().allMatch(value -> value.contains("GOOGLE")));
    }
    @Test
    void topNResearchCannotCompleteWithNarrativeClaimOrTooFewObservedSources() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "general-external-research",
                "Find and shortlist exactly 5 useful Vietnam Mother & Baby regulatory research items",
                "Vietnam consumer protection influencer trust risk scoring",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of(
                        "Exactly 5 substantive candidates",
                        "Each candidate ends in KEEP, TEST, CHANGE, or REJECT"),
                List.of("research-action:research.web.search", "externally attributable source URLs"));

        List<CognitiveWorkerRuntime.Cycle> history = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String url = "https://authority.example/source-" + i;
            history.add(new CognitiveWorkerRuntime.Cycle(
                    i,
                    new CognitiveWorkerRuntime.Thought(
                            GeneralWebResearchAction.ACTION_REF, Map.of("query", "Vietnam research " + i), "research"),
                    ActionFabric.ActionObservation.success(
                            GeneralWebResearchAction.ACTION_REF, "research result",
                            Map.of("researchResult", "result " + i), List.of(url)),
                    CognitiveWorkerRuntime.Reflection.continueWith("more evidence required")));
        }
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker-1", "assignment-1", "auth-1", "objective-1", work, "idem-1",
                List.of(GeneralWebResearchAction.ACTION_REF), history, Map.of());
        ActionFabric.ActionObservation latest = ActionFabric.ActionObservation.success(
                GeneralWebResearchAction.ACTION_REF, "research result",
                Map.of("researchResult", "result 4"), List.of("https://authority.example/source-4"));

        List<String> problems = GeneralCognitiveWorkerBrain.researchCompletionQualityProblems(
                context, latest,
                "We successfully identified and synthesized five candidates with KEEP, TEST, CHANGE, REJECT, KEEP.");

        assertTrue(problems.stream().anyMatch(value -> value.contains("5 distinct attributable research sources")));
        assertTrue(problems.stream().anyMatch(value -> value.contains("enumerated 5-item")));
        assertTrue(problems.stream().anyMatch(value -> value.contains("5 observed source URLs")));
    }

    @Test
    void topNResearchCompletionPassesOnlyWithEnumeratedObservedSourcesAndDecisions() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "general-external-research",
                "Find exactly 5 research items",
                "Vietnam Mother & Baby consumer protection",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("Each candidate ends in KEEP, TEST, CHANGE, or REJECT"),
                List.of("research-action:research.web.search"));

        List<CognitiveWorkerRuntime.Cycle> history = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            String url = "https://authority.example/source-" + i;
            history.add(new CognitiveWorkerRuntime.Cycle(
                    i,
                    new CognitiveWorkerRuntime.Thought(
                            GeneralWebResearchAction.ACTION_REF, Map.of("query", "Vietnam research " + i), "research"),
                    ActionFabric.ActionObservation.success(
                            GeneralWebResearchAction.ACTION_REF, "research result",
                            Map.of("researchResult", "result " + i), List.of(url)),
                    CognitiveWorkerRuntime.Reflection.continueWith("more evidence required")));
        }
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker-1", "assignment-1", "auth-1", "objective-1", work, "idem-1",
                List.of(GeneralWebResearchAction.ACTION_REF), history, Map.of());
        ActionFabric.ActionObservation latest = ActionFabric.ActionObservation.success(
                GeneralWebResearchAction.ACTION_REF, "research result",
                Map.of("researchResult", "result 5"), List.of("https://authority.example/source-5"));

        String summary = """
                1. Item one
                Source: https://authority.example/source-1
                Decision: KEEP
                2. Item two
                Source: https://authority.example/source-2
                Decision: TEST
                3. Item three
                Source: https://authority.example/source-3
                Decision: CHANGE
                4. Item four
                Source: https://authority.example/source-4
                Decision: KEEP
                5. Item five
                Source: https://authority.example/source-5
                Decision: REJECT
                Highest-potential experiment: test item two.
                """;

        assertTrue(GeneralCognitiveWorkerBrain.researchCompletionQualityProblems(context, latest, summary).isEmpty());
    }

}
