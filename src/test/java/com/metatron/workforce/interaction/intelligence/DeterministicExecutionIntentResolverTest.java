package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class DeterministicExecutionIntentResolverTest {
    private static final String REQUEST =
            "Execute a governed read-only repository audit of kelvinka38/bios and return evidence. Do not mutate anything.";

    private static FrontierSemanticInterpreter noProviderInterpreter() {
        return new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of()), provider -> "unused", List.of(), new ObjectMapper());
    }

    @Test
    void registeredReadOnlyRepositoryAuditDoesNotRequireAnLlmProvider() {
        NormalizedRequest normalized = noProviderInterpreter().interpret(
                REQUEST, "", "telegram", List.of("repository.audit.read"));

        assertEquals(IntelligenceMode.EXECUTION, normalized.mode());
        assertEquals("kelvinka38/bios", normalized.target());
        assertTrue(normalized.deterministicallyNormalized());
        assertNull(normalized.semanticProvider());
        assertEquals(1, normalized.executionWorkPlan().size());
        ExecutionWorkSpec step = normalized.executionWorkPlan().getFirst();
        assertEquals("repository.audit.read", step.requiredCapability());
        assertEquals(ExecutionWorkSpec.Consequence.READ_ONLY, step.consequence());
        assertEquals("kelvinka38/bios", step.target());
    }

    @Test
    void providerChannelDoesNotChangeDeterministicInstitutionalPlan() {
        NormalizedRequest telegram = noProviderInterpreter().interpret(
                REQUEST, "", "telegram", List.of("repository.audit.read"));
        NormalizedRequest zalo = noProviderInterpreter().interpret(
                REQUEST, "", "zalo", List.of("repository.audit.read"));

        assertEquals(telegram.objective(), zalo.objective());
        assertEquals(telegram.target(), zalo.target());
        assertEquals(telegram.executionWorkPlan(), zalo.executionWorkPlan());
        assertTrue(zalo.deterministicallyNormalized());
    }

    @Test
    void unavailableCapabilityStillFallsThroughToSemanticProvider() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> noProviderInterpreter().interpret(REQUEST, "", "telegram", List.of()));
        assertEquals("semantic_provider_required", failure.getMessage());
    }

    @Test
    void mutatingRepositoryRequestCannotUseReadOnlyDeterministicCapability() {
        String mutating = "Audit repository kelvinka38/bios and modify files as needed.";
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> noProviderInterpreter().interpret(
                        mutating, "", "telegram", List.of("repository.audit.read")));
        assertEquals("semantic_provider_required", failure.getMessage());
    }

    @Test
    void ordinaryUnknownIntentStillRequiresSemanticProvider() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> noProviderInterpreter().interpret(
                        "Explain why the project architecture is slow", "", "zalo",
                        List.of("repository.audit.read")));
        assertEquals("semantic_provider_required", failure.getMessage());
    }
}
