package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouterBackedIntelligenceEngineTest {

    @Test
    void translatesProviderNeutralRequestToExistingRouter() {
        LlmProviderClient client = new LlmProviderClient() {
            @Override
            public LlmProvider provider() {
                return LlmProvider.OPENAI;
            }

            @Override
            public LlmResponse complete(LlmRequest request) {
                assertEquals(LlmProvider.OPENAI, request.provider());
                assertEquals("test-model", request.model());
                assertTrue(request.systemContext().contains("requester=worker-1"));
                assertTrue(request.systemContext().contains("requiredCapability=audit"));
                assertEquals("audit G4 gateway", request.userInput());
                return new LlmResponse(request.provider(), request.model(), "audited", "ref-1");
            }
        };

        RouterBackedIntelligenceEngine engine = new RouterBackedIntelligenceEngine(
                new LlmProviderRouter(List.of(client)),
                provider -> "test-model");

        LlmResponse response = engine.execute(LlmProvider.OPENAI, new IntelligenceRequest(
                "r1",
                "worker-1",
                IntelligenceMode.REASONING,
                CollaborationMode.SINGLE,
                "audit G4 gateway",
                "gateway context",
                List.of("gateway:g4"),
                "audit",
                "high",
                "30s",
                "budget",
                "worker-authority",
                "audit report",
                List.of(LlmProvider.OPENAI),
                1));

        assertEquals("audited", response.text());
    }
}
