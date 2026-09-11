package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import com.metatron.workforce.interaction.tools.DefaultToolFabric;
import com.metatron.workforce.interaction.tools.ToolRequest;
import com.metatron.workforce.interaction.tools.ToolResult;
import com.metatron.workforce.interaction.tools.WebSearchToolAdapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Executes the post-independent phases of explicitly justified multi-model deliberation:
 * normalization -> contradiction map -> evidence acquisition when appropriate -> one targeted challenge round.
 * Initial provider responses remain independent. Consensus is never treated as correctness.
 */
public final class MultiModelDeliberationCoordinator {
    private final IntelligenceEngine engine;
    private final DefaultToolFabric tools;
    private final ObjectMapper mapper;

    public MultiModelDeliberationCoordinator(IntelligenceEngine engine, DefaultToolFabric tools, ObjectMapper mapper) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public DeliberationOutcome deliberate(IntelligenceRequest request, List<LlmResponse> independentResponses) {
        return deliberate(request, independentResponses,
                IntelligenceFabric.defaultBudget(request).withExplicitMultiModelRequest(request.maxProviders()));
    }

    public DeliberationOutcome deliberate(IntelligenceRequest request,
                                           List<LlmResponse> independentResponses,
                                           ProviderBudget providerBudget) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(independentResponses, "independentResponses");
        Objects.requireNonNull(providerBudget, "providerBudget");
        if (independentResponses.size() < 2) {
            return DeliberationOutcome.noop(independentResponses);
        }
        if (!providerBudget.multiModelAllowed()) {
            throw new IllegalStateException("multi_model_deliberation_requires_explicit_budget");
        }

        Assessment assessment;
        LlmResponse normalizationResponse;
        try {
            LlmProvider normalizer = independentResponses.getFirst().provider();
            IntelligenceRequest normalizationRequest = singleProviderRequest(
                    request,
                    "deliberation-normalize-" + request.requestId(),
                    normalizer,
                    normalizationObjective(independentResponses),
                    request.context(),
                    request.evidenceReferences(),
                    "deliberation-normalization");
            normalizationResponse = engine.execute(
                    normalizer, normalizationRequest, EscalationReason.EXPLICIT_HUMAN_REQUEST, providerBudget);
            assessment = parseAssessment(normalizationResponse.text());
        } catch (RuntimeException failure) {
            return DeliberationOutcome.noop(independentResponses);
        }

        if (!assessment.materialDisagreement()) {
            return new DeliberationOutcome(
                    independentResponses,
                    independentResponses,
                    assessment,
                    normalizationResponse,
                    null,
                    List.of(),
                    false);
        }

        ToolResult additionalEvidence = null;
        if (!assessment.externalEvidenceQuery().isBlank()) {
            additionalEvidence = acquireExternalEvidence(request, assessment.externalEvidenceQuery());
        }

        String challengeContext = challengeContext(request, independentResponses, assessment, additionalEvidence);
        Map<LlmProvider, LlmResponse> revisedByProvider = new LinkedHashMap<>();
        for (LlmResponse initial : independentResponses) {
            try {
                IntelligenceRequest challengeRequest = singleProviderRequest(
                        request,
                        "deliberation-challenge-" + request.requestId() + "-" + initial.provider().name(),
                        initial.provider(),
                        challengeObjective(request, assessment),
                        challengeContext,
                        mergeEvidence(request.evidenceReferences(), additionalEvidence),
                        "targeted-challenge");
                LlmResponse revised = engine.execute(
                        initial.provider(), challengeRequest, EscalationReason.MATERIAL_CONTRADICTION, providerBudget);
                revisedByProvider.put(initial.provider(), revised);
            } catch (RuntimeException ignored) {
                revisedByProvider.put(initial.provider(), initial);
            }
        }

        List<LlmResponse> revised = independentResponses.stream()
                .map(initial -> revisedByProvider.getOrDefault(initial.provider(), initial))
                .toList();
        List<String> addedEvidence = additionalEvidence != null && additionalEvidence.success()
                ? additionalEvidence.evidenceReferences() : List.of();
        return new DeliberationOutcome(
                independentResponses,
                revised,
                assessment,
                normalizationResponse,
                additionalEvidence,
                addedEvidence,
                true);
    }

    public String renderPrelude(DeliberationOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome.assessment() == null) return "";
        StringBuilder out = new StringBuilder("MULTI-MODEL DELIBERATION CONTROL\n")
                .append("initial_independence=preserved\n")
                .append("material_disagreement=").append(outcome.assessment().materialDisagreement()).append('\n')
                .append("disputed_questions=").append(outcome.assessment().disputedQuestions()).append('\n')
                .append("unsupported_claims=").append(outcome.assessment().unsupportedClaims()).append('\n')
                .append("missing_evidence=").append(outcome.assessment().missingEvidence()).append('\n')
                .append("unresolved_disagreement=").append(outcome.assessment().unresolvedDisagreement()).append('\n')
                .append("normalization_note=This map is a model-produced reasoning artifact, not evidence or truth.\n");
        if (outcome.additionalEvidence() != null) {
            out.append("additional_evidence_status=").append(outcome.additionalEvidence().success()).append('\n')
                    .append("additional_evidence_refs=").append(outcome.addedEvidenceReferences()).append('\n');
        }
        out.append("targeted_challenge_round=").append(outcome.challengeRoundExecuted()).append('\n')
                .append("stop_rule=one targeted challenge round maximum; unresolved disagreement remains explicit\n");
        return out.toString().trim();
    }

    private Assessment parseAssessment(String responseText) {
        try {
            JsonNode root = mapper.readTree(unwrapJson(responseText));
            return new Assessment(
                    root.path("material_disagreement").asBoolean(false),
                    textArray(root, "disputed_questions"),
                    textArray(root, "unsupported_claims"),
                    textArray(root, "missing_evidence"),
                    optionalText(root, "external_evidence_query"),
                    optionalText(root, "unresolved_disagreement"));
        } catch (Exception failure) {
            throw new IllegalStateException("invalid multi-model normalization", failure);
        }
    }

    private ToolResult acquireExternalEvidence(IntelligenceRequest request, String query) {
        ToolRequest toolRequest = new ToolRequest(
                "deliberation-web-" + request.requestId(),
                request.requester(),
                WebSearchToolAdapter.CAPABILITY,
                "internet:web-search",
                "search",
                query,
                request.authorityContext().isBlank() ? List.of() : List.of(request.authorityContext()));
        try {
            return tools.execute(toolRequest);
        } catch (RuntimeException failure) {
            return ToolResult.failure(toolRequest, "deliberation_evidence_acquisition_failed:" + failure.getClass().getSimpleName());
        }
    }

    private static IntelligenceRequest singleProviderRequest(
            IntelligenceRequest base,
            String requestId,
            LlmProvider provider,
            String objective,
            String context,
            List<String> evidence,
            String capability) {
        return new IntelligenceRequest(
                requestId,
                base.requester(),
                IntelligenceMode.REASONING,
                CollaborationMode.SINGLE,
                objective,
                context,
                evidence,
                capability,
                base.consequence(),
                base.latencyBudget(),
                base.costBudget(),
                base.authorityContext(),
                "structured deliberation artifact",
                List.of(provider),
                1,
                false);
    }

    private static String normalizationObjective(List<LlmResponse> responses) {
        StringBuilder out = new StringBuilder("Normalize the following independent provider analyses. Return ONLY JSON with fields: material_disagreement (boolean), disputed_questions (array of strings), unsupported_claims (array), missing_evidence (array), external_evidence_query (string or empty), unresolved_disagreement (string or empty). Do not vote truth by majority. Set external_evidence_query only when public/current external evidence can materially resolve a knowable dispute.\n\n");
        for (int i = 0; i < responses.size(); i++) {
            LlmResponse response = responses.get(i);
            out.append("PROVIDER_").append(i + 1).append(" [").append(response.provider()).append("]\n")
                    .append(response.text()).append("\n\n");
        }
        return out.toString();
    }

    private static String challengeObjective(IntelligenceRequest request, Assessment assessment) {
        return "Re-evaluate your independent analysis for the original objective: " + request.objective()
                + "\nAddress only the material disputed questions: " + assessment.disputedQuestions()
                + "\nUse supplied evidence. Explicitly state what changed, what remains unresolved, and do not claim consensus as proof.";
    }

    private static String challengeContext(IntelligenceRequest request,
                                           List<LlmResponse> independent,
                                           Assessment assessment,
                                           ToolResult evidence) {
        StringBuilder out = new StringBuilder(request.context())
                .append("\n\nDELIBERATION DISPUTE MAP (reasoning artifact, not evidence):\n")
                .append("disputed_questions=").append(assessment.disputedQuestions()).append('\n')
                .append("unsupported_claims=").append(assessment.unsupportedClaims()).append('\n')
                .append("missing_evidence=").append(assessment.missingEvidence()).append('\n')
                .append("unresolved_disagreement=").append(assessment.unresolvedDisagreement()).append('\n')
                .append("\nINITIAL INDEPENDENT RESPONSES:\n");
        for (LlmResponse response : independent) {
            out.append('[').append(response.provider()).append("]\n").append(response.text()).append("\n\n");
        }
        if (evidence != null) {
            out.append("ADDITIONAL GROUNDING EVIDENCE:\n")
                    .append("success=").append(evidence.success()).append('\n')
                    .append("refs=").append(evidence.evidenceReferences()).append('\n')
                    .append(evidence.output()).append('\n');
        }
        return out.toString();
    }

    private static List<String> mergeEvidence(List<String> base, ToolResult additional) {
        Set<String> refs = new LinkedHashSet<>(base);
        if (additional != null && additional.success()) refs.addAll(additional.evidenceReferences());
        return List.copyOf(refs);
    }

    private static String unwrapJson(String text) {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) value = value.substring(firstNewline + 1, lastFence).trim();
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalStateException("deliberation normalization is not JSON");
        return value.substring(start, end + 1);
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isTextual() ? node.asText().trim() : "";
    }

    private static List<String> textArray(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank()) values.add(item.asText().trim());
        });
        return List.copyOf(values);
    }

    public record Assessment(
            boolean materialDisagreement,
            List<String> disputedQuestions,
            List<String> unsupportedClaims,
            List<String> missingEvidence,
            String externalEvidenceQuery,
            String unresolvedDisagreement) {
        public Assessment {
            Objects.requireNonNull(disputedQuestions, "disputedQuestions");
            Objects.requireNonNull(unsupportedClaims, "unsupportedClaims");
            Objects.requireNonNull(missingEvidence, "missingEvidence");
            Objects.requireNonNull(externalEvidenceQuery, "externalEvidenceQuery");
            Objects.requireNonNull(unresolvedDisagreement, "unresolvedDisagreement");
            disputedQuestions = List.copyOf(disputedQuestions);
            unsupportedClaims = List.copyOf(unsupportedClaims);
            missingEvidence = List.copyOf(missingEvidence);
        }
    }

    public record DeliberationOutcome(
            List<LlmResponse> independentResponses,
            List<LlmResponse> responsesForSynthesis,
            Assessment assessment,
            LlmResponse normalizationResponse,
            ToolResult additionalEvidence,
            List<String> addedEvidenceReferences,
            boolean challengeRoundExecuted) {
        public DeliberationOutcome {
            Objects.requireNonNull(independentResponses, "independentResponses");
            Objects.requireNonNull(responsesForSynthesis, "responsesForSynthesis");
            Objects.requireNonNull(addedEvidenceReferences, "addedEvidenceReferences");
            independentResponses = List.copyOf(independentResponses);
            responsesForSynthesis = List.copyOf(responsesForSynthesis);
            addedEvidenceReferences = List.copyOf(addedEvidenceReferences);
        }

        static DeliberationOutcome noop(List<LlmResponse> responses) {
            return new DeliberationOutcome(responses, responses, null, null, null, List.of(), false);
        }
    }
}
