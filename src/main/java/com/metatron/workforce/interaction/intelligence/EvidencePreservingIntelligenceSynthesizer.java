package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic multi-provider synthesis that preserves independent provider attribution.
 *
 * This component deliberately does not vote, score truth, or create evidence/authority.
 * It produces one governed review artifact while keeping disagreements visible for BIOS
 * and downstream human/institutional review.
 */
public final class EvidencePreservingIntelligenceSynthesizer implements IntelligenceSynthesizer {

    @Override
    public String synthesize(IntelligenceRequest request, List<LlmResponse> responses) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(responses, "responses");
        if (responses.size() < 2) {
            throw new IllegalArgumentException("multi-model synthesis requires at least two provider responses");
        }

        return switch (request.collaborationMode()) {
            case SINGLE -> throw new IllegalArgumentException("SINGLE mode must not invoke multi-model synthesis");
            case LEAD_REVIEW -> leadReview(responses);
            case CONSENSUS -> parallelSynthesis(responses);
            case INDEPENDENT_SECOND_OPINION -> independentSecondOpinion(responses);
            case ADVERSARIAL_REVIEW -> adversarialReview(responses);
        };
    }

    private static String leadReview(List<LlmResponse> responses) {
        LlmResponse lead = responses.getFirst();
        StringBuilder out = new StringBuilder("LEAD + REVIEW\n\nLEAD [")
                .append(lead.provider()).append("]\n").append(lead.text().trim());
        for (int i = 1; i < responses.size(); i++) {
            append(out, "REVIEWER " + i, responses.get(i));
        }
        return boundary(out);
    }

    private static String parallelSynthesis(List<LlmResponse> responses) {
        StringBuilder out = new StringBuilder("PARALLEL + SYNTHESIS\n");
        for (int i = 0; i < responses.size(); i++) append(out, "PROPOSAL " + (i + 1), responses.get(i));
        return boundary(out);
    }

    private static String independentSecondOpinion(List<LlmResponse> responses) {
        StringBuilder out = new StringBuilder("INDEPENDENT SECOND OPINION\n");
        append(out, "PRIMARY", responses.getFirst());
        append(out, "SECOND OPINION", responses.get(1));
        for (int i = 2; i < responses.size(); i++) append(out, "ADDITIONAL OPINION " + i, responses.get(i));
        return boundary(out);
    }

    private static String adversarialReview(List<LlmResponse> responses) {
        StringBuilder out = new StringBuilder("ADVERSARIAL REVIEW\n");
        append(out, "PROPOSAL", responses.getFirst());
        for (int i = 1; i < responses.size(); i++) append(out, "CHALLENGE " + i, responses.get(i));
        return boundary(out);
    }

    private static void append(StringBuilder out, String role, LlmResponse response) {
        out.append("\n\n").append(role).append(" [").append(response.provider()).append("]\n")
                .append(response.text().trim());
    }

    private static String boundary(StringBuilder out) {
        return out.append("\n\nGOVERNANCE NOTE\n")
                .append("Provider agreement is not evidence and disagreement is preserved. ")
                .append("This synthesis creates neither institutional truth nor authority.")
                .toString();
    }
}
