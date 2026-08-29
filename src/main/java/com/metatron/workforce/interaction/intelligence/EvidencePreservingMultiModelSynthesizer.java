package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Production-safe multi-model synthesis that preserves independent provider attribution.
 *
 * This component deliberately does not use majority voting and does not promote model
 * agreement to evidence or truth. When independent outputs differ, the disagreement is
 * surfaced rather than silently adjudicated by another ungrounded model call.
 */
public final class EvidencePreservingMultiModelSynthesizer implements IntelligenceSynthesizer {

    @Override
    public String synthesize(IntelligenceRequest request, List<LlmResponse> responses) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(responses, "responses");
        if (responses.size() < 2) {
            throw new IllegalStateException("MULTI_MODEL_SYNTHESIS_REQUIRES_MULTIPLE_RESPONSES");
        }

        return switch (request.collaborationMode()) {
            case SINGLE -> throw new IllegalStateException("SINGLE_MODE_MUST_NOT_USE_MULTI_MODEL_SYNTHESIS");
            case CONSENSUS -> renderConsensus(responses);
            case LEAD_REVIEW -> renderLeadReview(responses);
        };
    }

    private static String renderConsensus(List<LlmResponse> responses) {
        boolean exactAgreement = responses.stream()
                .map(LlmResponse::text)
                .map(EvidencePreservingMultiModelSynthesizer::normalize)
                .distinct()
                .count() == 1;

        StringBuilder out = new StringBuilder("MULTI-MODEL INDEPENDENT REVIEW\n");
        out.append("status=").append(exactAgreement ? "EXACT_TEXT_AGREEMENT" : "DIVERGENT_OUTPUTS").append('\n');
        out.append("note=Model agreement is not evidence or truth; divergent outputs are preserved for review.\n");
        appendResponses(out, responses, "PROPOSAL");
        return out.toString().trim();
    }

    private static String renderLeadReview(List<LlmResponse> responses) {
        StringBuilder out = new StringBuilder("MULTI-MODEL LEAD + REVIEW\n");
        LlmResponse lead = responses.getFirst();
        out.append("LEAD [").append(lead.provider()).append(" / ").append(lead.model()).append("]\n")
                .append(lead.text().trim()).append("\n");
        for (int i = 1; i < responses.size(); i++) {
            LlmResponse reviewer = responses.get(i);
            out.append("\nREVIEWER ").append(i).append(" [")
                    .append(reviewer.provider()).append(" / ").append(reviewer.model()).append("]\n")
                    .append(reviewer.text().trim()).append("\n");
        }
        out.append("\nnote=Reviewer output does not create authority and disagreement is not resolved by majority vote.");
        return out.toString().trim();
    }

    private static void appendResponses(StringBuilder out, List<LlmResponse> responses, String label) {
        for (int i = 0; i < responses.size(); i++) {
            LlmResponse response = responses.get(i);
            out.append("\n").append(label).append(' ').append(i + 1).append(" [")
                    .append(response.provider()).append(" / ").append(response.model()).append("]\n")
                    .append(response.text().trim()).append("\n");
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
