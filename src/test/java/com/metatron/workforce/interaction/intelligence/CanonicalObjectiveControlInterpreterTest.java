package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CanonicalObjectiveControlInterpreterTest {
    private static final String GS1 =
            "Take ownership of one Objective: perform a governed read-only institutional audit of "
                    + "kelvinka38/universal, kelvinka38/metatron-institution, kelvinka38/metatron-workforce, "
                    + "and kelvinka38/bios. Independently plan the work, audit all four repositories in parallel "
                    + "where dependencies allow, join the results, analyze contradictions, verify every acceptance "
                    + "criterion through Observation, and deliver one evidence-backed completion. Do not mutate anything.";

    private static final String GS2 =
            "Take ownership of one governed general engineering Objective against kelvinka38/metatron-workforce "
                    + "at exact source commit bafeeb764ab07fe488fd8ce0856268b1be319ac9. Materialize that exact repository "
                    + "snapshot into the Objective workspace. Create or replace only "
                    + "docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md with a short proof containing exact source SHA "
                    + "bafeeb764ab07fe488fd8ce0856268b1be319ac9. Run the repository test suite through the governed "
                    + "test action and require it to pass. Stage only that proof file and create one local Git commit "
                    + "as the immutable work product. Verify the result through independent Observation. "
                    + "Do not push, do not open a pull request, do not modify any remote repository state.";

    @Test
    void gs1ControlNormalizesWithoutFrontierProvider() {
        NormalizedRequest normalized = CanonicalObjectiveControlInterpreter.interpret(GS1).orElseThrow();

        assertEquals(IntelligenceMode.EXECUTION, normalized.mode());
        assertEquals(IntelligenceDepth.DEEP, normalized.requestedDepth());
        assertEquals(
                "kelvinka38/universal,kelvinka38/metatron-institution,kelvinka38/metatron-workforce,kelvinka38/bios",
                normalized.target());
        assertTrue(normalized.constraints().contains(GS1));
        assertTrue(normalized.explicitProhibitions().stream().anyMatch(v ->
                v.toLowerCase().contains("do not mutate anything")));
        assertTrue(normalized.analyticalProtocols().contains(AnalyticalProtocolType.AUDIT));
        assertEquals(CaseContinuity.NEW, normalized.caseContinuity());
        assertNull(normalized.semanticProvider());
        assertNull(normalized.explicitlyRequestedProvider());
    }

    @Test
    void gs2ControlPreservesExactEngineeringContract() {
        NormalizedRequest normalized = CanonicalObjectiveControlInterpreter.interpret(GS2).orElseThrow();

        assertEquals(IntelligenceMode.EXECUTION, normalized.mode());
        assertEquals("kelvinka38/metatron-workforce", normalized.target());
        assertTrue(normalized.constraints().getFirst().contains("GS2_GENERAL_RUNTIME_PROOF.md"));
        String prohibitions = String.join(" | ", normalized.explicitProhibitions()).toLowerCase();
        assertTrue(prohibitions.contains("do not push"));
        assertTrue(prohibitions.contains("do not open a pull request"));
        assertTrue(prohibitions.contains("do not modify any remote repository state"));
        assertNull(normalized.semanticProvider());
    }

    @Test
    void explicitObjectiveControlPreemptsMissingSemanticProvidersAndHandsOff() {
        AtomicReference<NormalizedRequest> captured = new AtomicReference<>();
        ExecutionObjectiveHandoff handoff = new ExecutionObjectiveHandoff() {
            @Override
            public HandoffReceipt submit(
                    String humanId,
                    String organizationContextId,
                    String caseId,
                    String conversationId,
                    String externalMessageReference,
                    String channel,
                    NormalizedRequest request) {
                captured.set(request);
                return new HandoffReceipt(
                        true,
                        "objective:canonical-control",
                        "metatron-workforce",
                        "queue:canonical-control",
                        "ACCEPTED",
                        "ACCEPTED",
                        "OBJECTIVE_ACCEPTED_FOR_AUTONOMOUS_MANAGEMENT");
            }
        };

        MetatronIntelligenceResponder responder = new MetatronIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper(),
                "", "", new InMemoryIntelligenceCaseStore(), handoff);

        String answer = responder.respond(
                "human-primary",
                GS1,
                "telegram:update:canonical-control",
                "telegram",
                "conversation:human:human-primary",
                "organization:metatron",
                "");

        assertTrue(answer.startsWith("METATRON WORK ACCEPTED"));
        assertTrue(answer.contains("objective_id=objective:canonical-control"));
        assertEquals(IntelligenceMode.EXECUTION, captured.get().mode());
        assertEquals(
                "kelvinka38/universal,kelvinka38/metatron-institution,kelvinka38/metatron-workforce,kelvinka38/bios",
                captured.get().target());
        assertNull(captured.get().semanticProvider());
    }

    @Test
    void ordinaryNaturalLanguageDoesNotBypassSemanticBoundary() {
        assertFalse(CanonicalObjectiveControlInterpreter.isExplicitObjectiveControl(
                "Please audit the four repositories and tell me what you find."));
        assertTrue(CanonicalObjectiveControlInterpreter.interpret(
                "Please audit the four repositories and tell me what you find.").isEmpty());
    }
}
