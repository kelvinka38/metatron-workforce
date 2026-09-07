package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    private static final String GS3A =
            "Take ownership of one bounded READ_ONLY production recovery Objective. "
                    + "Use the available composite capability autonomy.recovery.probe.read against exact target "
                    + "p10-recovery://transient-timeout/34000131648-178865282691104005. "
                    + "The capability intentionally injects one transient provider timeout. Recover autonomously without "
                    + "Human resume, preserve durable attempt/runtime/fencing evidence, independently verify the acceptance "
                    + "criterion through Observation, and complete. Do not mutate anything and do not invent lower-level capabilities.";

    private static final String GS3B =
            "Take ownership of one bounded READ_ONLY restart-recovery Objective. "
                    + "Use the available composite capability autonomy.recovery.probe.read against exact target "
                    + "p10-recovery://restart-window/34000131648-178865282691104006. "
                    + "Hold the governed Work dispatch open for the production restart fault window, then recover from "
                    + "runtime/process loss without Human operation, independently verify Observation, and complete. "
                    + "Do not mutate anything and do not invent lower-level capabilities.";

    private static final String GS3C =
            "Take ownership of one bounded READ_ONLY Observation-recovery Objective. "
                    + "Use the available composite capability autonomy.recovery.probe.read against exact target "
                    + "p10-recovery://observation-retry/34000131648-178865282691104007. "
                    + "Execution completes once, while independent Observation intentionally begins with insufficient "
                    + "evidence and must retry autonomously until authoritative evidence settles. Complete only after "
                    + "Observation PASS. Do not mutate anything.";

    private static final String GS4 =
            "Take ownership of one bounded-elasticity Objective: perform governed READ_ONLY audits of "
                    + "kelvinka38/universal, kelvinka38/metatron-institution, kelvinka38/metatron-workforce, and "
                    + "kelvinka38/bios as independent Work where dependencies allow; schedule them concurrently within "
                    + "finite capacity, then join all four results using cross-repository-audit-analysis, verify through "
                    + "Observation, and complete with evidence. Do not mutate anything.";

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
    void boundedRecoveryControlsPreserveExactRuntimeTargetsWithoutFrontierProvider() {
        List<String> controls = List.of(GS3A, GS3B, GS3C);
        List<String> expectedTargets = List.of(
                "p10-recovery://transient-timeout/34000131648-178865282691104005",
                "p10-recovery://restart-window/34000131648-178865282691104006",
                "p10-recovery://observation-retry/34000131648-178865282691104007");

        for (int i = 0; i < controls.size(); i++) {
            String control = controls.get(i);
            assertTrue(CanonicalObjectiveControlInterpreter.isExplicitObjectiveControl(control));
            NormalizedRequest normalized = CanonicalObjectiveControlInterpreter.interpret(control).orElseThrow();
            assertEquals(IntelligenceMode.EXECUTION, normalized.mode());
            assertEquals(IntelligenceDepth.DEEP, normalized.requestedDepth());
            assertEquals(expectedTargets.get(i), normalized.target());
            assertTrue(normalized.constraints().contains(control));
            assertNull(normalized.semanticProvider());
            assertNull(normalized.explicitlyRequestedProvider());
        }
    }

    @Test
    void boundedElasticityControlPreservesFourRepositoryScope() {
        assertTrue(CanonicalObjectiveControlInterpreter.isExplicitObjectiveControl(GS4));
        NormalizedRequest normalized = CanonicalObjectiveControlInterpreter.interpret(GS4).orElseThrow();

        assertEquals(
                "kelvinka38/universal,kelvinka38/metatron-institution,kelvinka38/metatron-workforce,kelvinka38/bios",
                normalized.target());
        assertTrue(normalized.analyticalProtocols().contains(AnalyticalProtocolType.AUDIT));
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
                GS3A,
                "telegram:update:canonical-control",
                "telegram",
                "conversation:human:human-primary",
                "organization:metatron",
                "");

        assertTrue(answer.startsWith("METATRON WORK ACCEPTED"));
        assertTrue(answer.contains("objective_id=objective:canonical-control"));
        assertEquals(IntelligenceMode.EXECUTION, captured.get().mode());
        assertEquals(
                "p10-recovery://transient-timeout/34000131648-178865282691104005",
                captured.get().target());
        assertNull(captured.get().semanticProvider());
    }

    @Test
    void explicitWorkAdmissionBypassesSemanticChatKillSwitchButSemanticExecutionDoesNot() {
        assertTrue(MetatronIntelligenceResponder.shouldAdmitExecution(true, false),
                "deterministic explicit Objective controls must remain executable");
        assertFalse(MetatronIntelligenceResponder.shouldAdmitExecution(false, false),
                "semantic chat-to-Work remains disabled by default");
        assertTrue(MetatronIntelligenceResponder.shouldAdmitExecution(false, true),
                "an explicit product configuration may enable semantic durable Work admission");
    }

    @Test
    void ordinaryNaturalLanguageDoesNotBypassSemanticBoundary() {
        assertFalse(CanonicalObjectiveControlInterpreter.isExplicitObjectiveControl(
                "Please audit the four repositories and tell me what you find."));
        assertTrue(CanonicalObjectiveControlInterpreter.interpret(
                "Please audit the four repositories and tell me what you find.").isEmpty());
        assertFalse(CanonicalObjectiveControlInterpreter.isExplicitObjectiveControl(
                "Take ownership of one bounded task and tell me what happens."));
    }
}
