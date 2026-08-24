package com.metatron.workforce.interaction.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmProviderRouterTest {
    @Test
    void routesOnlyToExplicitlySelectedConfiguredProvider() {
        LlmProviderClient openai = new StubClient(LlmProvider.OPENAI);
        LlmProviderClient anthropic = new StubClient(LlmProvider.ANTHROPIC);
        LlmProviderRouter router = new LlmProviderRouter(List.of(openai, anthropic));

        LlmResponse response = router.complete(new LlmRequest(
                LlmProvider.ANTHROPIC, "test-model", "context", "hello"));

        assertEquals(LlmProvider.ANTHROPIC, response.provider());
        assertEquals("test-model", response.model());
    }

    @Test
    void unconfiguredProviderIsDeniedWithoutFallback() {
        LlmProviderRouter router = new LlmProviderRouter(List.of(new StubClient(LlmProvider.OPENAI)));

        assertThrows(IllegalStateException.class, () -> router.complete(new LlmRequest(
                LlmProvider.GOOGLE, "test-model", "context", "hello")));
    }

    @Test
    void duplicateProviderRegistrationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LlmProviderRouter(List.of(
                new StubClient(LlmProvider.OPENAI), new StubClient(LlmProvider.OPENAI))));
    }

    private record StubClient(LlmProvider provider) implements LlmProviderClient {
        @Override
        public LlmResponse complete(LlmRequest request) {
            return new LlmResponse(provider, request.model(), "stub-response", "stub://request");
        }
    }
}
