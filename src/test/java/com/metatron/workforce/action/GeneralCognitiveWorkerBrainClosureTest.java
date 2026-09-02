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
}
