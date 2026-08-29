package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Production-safe multi-model synthesis that preserves independent provider attribution.
 * Agreement is never promoted to evidence, truth, or authority.
 */
public final class EvidencePreservingMultiModelSynthesizer implements IntelligenceSynthesizer {
    @Override
    public String synthesize(IntelligenceRequest request, List<LlmResponse> responses) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(responses, "responses");
        if (responses.size() < 2) throw new IllegalStateException("MULTI_MODEL_SYNTHESIS_REQUIRES_MULTIPLE_RESPONSES");

        return switch (request.collaborationMode()) {
            case SINGLE -> throw new IllegalStateException("SINGLE_MODE_MUST_NOT_USE_MULTI_MODEL_SYNTHESIS");
            case CONSENSUS -> renderConsensus(responses);
            case LEAD_REVIEW -> renderLeadReview(responses);
            case INDEPENDENT_SECOND_OPINION -> renderIndependentSecondOpinion(responses);
            case ADVERSARIAL_REVIEW -> renderAdversarialReview(responses);
        };
    }

    private static String renderConsensus(List<LlmResponse> responses) {
        boolean exactAgreement = responses.stream().map(LlmResponse::text).map(EvidencePreservingMultiModelSynthesizer::normalize).distinct().count() == 1;
        StringBuilder out = new StringBuilder("MULTI-MODEL INDEPENDENT REVIEW\n");
        out.append("status=").append(exactAgreement ? "EXACT_TEXT_AGREEMENT" : "DIVERGENT_OUTPUTS").append('\n');
        out.append("note=Model agreement is not evidence or truth; divergent outputs are preserved for review.\n");
        appendResponses(out, responses, "PROPOSAL");
        return out.toString().trim();
    }

    private static String renderLeadReview(List<LlmResponse> responses) {
        StringBuilder out = new StringBuilder("MULTI-MODEL LEAD + REVIEW\n");
        appendOne(out, "LEAD", responses.getFirst());
        for (int i = 1; i < responses.size(); i++) appendOne(out, "REVIEWER " + i, responses.get(i));
        return boundary(out);
    }

    private static String renderIndependentSecondOpinion(List<LlmResponse> responses) {
        StringBuilder out = new StringBuilder("MULTI-MODEL INDEPENDENT SECOND OPINION\n");
        appendOne(out, "PRIMARY", responses.getFirst());
        appendOne(out, "SECOND OPINION", responses.get(1));
        for (int i = 2; i < responses.size(); i++) appendOne(out, "ADDITIONAL OPINION " + i, responses.get(i));
        return boundary(out);
    }

    private static String renderAdversarialReview(List<LlmResponse> responses) {
        StringBuilder out = new StringBuilder("MULTI-MODEL ADVERSARIAL REVIEW\n");
        appendOne(out, "PROPOSAL", responses.getFirst());
        for (int i = 1; i < responses.size(); i++) appendOne(out, "CHALLENGE " + i, responses.get(i));
        return boundary(out);
    }

    private static void appendResponses(StringBuilder out, List<LlmResponse> responses, String label) {
        for (int i = 0; i < responses.size(); i++) appendOne(out, label + " " + (i + 1), responses.get(i));
    }

    private static void appendOne(StringBuilder out, String role, LlmResponse response) {
        out.append("\n").append(role).append(" [").append(response.provider()).append(" / ").append(response.model()).append("]\n")
                .append(response.text().trim()).append("\n");
    }

    private static String boundary(StringBuilder out) {
        return out.append("\nnote=Model output does not create evidence, institutional truth, or authority; disagreement is preserved and not resolved by majority vote.").toString().trim();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
