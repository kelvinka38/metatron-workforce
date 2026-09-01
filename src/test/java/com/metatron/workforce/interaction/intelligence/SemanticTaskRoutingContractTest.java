package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Root routing invariants: terminal product and evidence scope, not topic keywords, decide the path. */
final class SemanticTaskRoutingContractTest {

    @Test
    void answerThatNeedsCurrentRealityCannotBecomeDurableObjectiveEvenIfProviderProposesExecution() {
        NormalizedRequest request = interpret(json(
                "identify the currently published stable version of an arbitrary software package",
                "ANSWER", "CURRENT_EXTERNAL", "EXECUTION", false));

        assertEquals(IntelligenceMode.REASONING, request.mode());
        assertTrue(request.freshExternalDataRequired());
        assertFalse(request.canReturnFastDirectly());
    }

    @Test
    void durableInstitutionalWorkCannotBeDowngradedToReasoningEvenIfProviderProposesReasoning() {
        NormalizedRequest request = interpret(json(
                "inspect the repository, repair the failing production path, verify it and deliver the outcome",
                "DURABLE_WORK", "INSTITUTIONAL", "REASONING", false));

        assertEquals(IntelligenceMode.EXECUTION, request.mode());
        assertFalse(request.freshExternalDataRequired());
    }

    @Test
    void currentEvidenceScopeCanonicalizesFreshnessWithoutAnyDomainVocabulary() {
        NormalizedRequest request = interpret(json(
                "answer the Human using the present external state of the referenced subject",
                "ANSWER", "CURRENT_EXTERNAL", "REASONING", false));

        assertEquals(IntelligenceMode.REASONING, request.mode());
        assertTrue(request.freshExternalDataRequired());
    }

    @Test
    void ordinaryAnswerDoesNotAcquireObjectiveOwnership() {
        NormalizedRequest request = interpret(json(
                "explain the distinction between throughput and latency",
                "ANSWER", "NONE", "DISCUSSION", false));

        assertEquals(IntelligenceMode.DISCUSSION, request.mode());
        assertFalse(request.freshExternalDataRequired());
    }

    @Test
    void casualConversationRemainsCasualAndOutsideObjectiveOwnership() {
        NormalizedRequest request = interpret(json(
                "greet the Human conversationally",
                "ANSWER", "NONE", "CASUAL", false));

        assertEquals(IntelligenceMode.CASUAL, request.mode());
        assertFalse(request.freshExternalDataRequired());
    }

    @Test
    void institutionalDecisionIsKeptSeparateFromExecutionAndReasoning() {
        NormalizedRequest request = interpret(json(
                "approve the institutional release decision",
                "INSTITUTIONAL_DECISION", "INSTITUTIONAL", "REASONING", false));

        assertEquals(IntelligenceMode.DECISION, request.mode());
    }

    private static NormalizedRequest interpret(String responseJson) {
        LlmProviderClient provider = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(LlmProvider.GOOGLE, "semantic-contract", responseJson, "semantic-ref");
            }
        };
        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of(provider)), ignored -> "semantic-contract",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());
        return interpreter.interpret("opaque human utterance", "", "telegram");
    }

    private static String json(String objective, String outcome, String evidenceScope,
                               String proposedMode, boolean proposedFresh) {
        return """
                {
                  "objective":"%s",
                  "target":"arbitrary subject",
                  "constraints":[],
                  "requested_depth":"ANALYZE",
                  "requested_output":"direct natural-language answer",
                  "explicit_assumptions":[],
                  "explicit_prohibitions":[],
                  "temporal_context":"",
                  "unresolved_semantic_ambiguity":"",
                  "interaction_outcome":"%s",
                  "evidence_scope":"%s",
                  "mode":"%s",
                  "collaboration_mode":"SINGLE",
                  "analytical_protocols":[],
                  "deterministic_capability":"NONE",
                  "deterministic_computations":[],
                  "fresh_external_data_required":%s,
                  "explicitly_requested_provider":null,
                  "case_continuity":"NEW",
                  "direct_response":""
                }
                """.formatted(objective, outcome, evidenceScope, proposedMode, proposedFresh);
    }
}
