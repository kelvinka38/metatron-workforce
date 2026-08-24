package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic Workforce runtime adapter for the canonical BIOS governance boundary.
 *
 * BIOS is not an LLM prompt. This validator enforces the machine-checkable invariants
 * that can be proven from the Workforce intelligence envelope without inventing
 * upstream BIOS semantics or pretending model output is reality.
 */
public final class BiosConformanceValidator {
    private static final Set<String> GOVERNED_CONSEQUENCES = Set.of("MEDIUM", "HIGH", "CRITICAL", "IRREVERSIBLE");
    private static final Set<String> HIGH_CONSEQUENCE = Set.of("HIGH", "CRITICAL", "IRREVERSIBLE");
    private static final Set<String> MULTI_ENGINE_REQUIRED = Set.of("CRITICAL", "IRREVERSIBLE");

    public void validate(IntelligenceRequest request, List<LlmResponse> responses, String finalText) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(responses, "responses");
        Objects.requireNonNull(finalText, "finalText");

        validateRequest(request);
        validateResponses(request, responses);

        if (finalText.isBlank()) {
            throw new IllegalStateException("BIOS_OUTPUT_EMPTY");
        }

        // BIOS invariant: a model result is not execution evidence.
        // The intelligence layer may recommend/support execution, but it cannot
        // manufacture authority or success from its own output.
        if (request.mode() == IntelligenceMode.EXECUTION && isBlank(request.authorityContext())) {
            throw new IllegalStateException("BIOS_EXECUTION_AUTHORITY_REQUIRED");
        }
    }

    private void validateRequest(IntelligenceRequest request) {
        if (request.mode().requiresGovernance()) {
            requireEvidence(request.evidenceReferences());
        }

        String consequence = normalize(request.consequence());
        if (consequence.isBlank()) {
            throw new IllegalStateException("BIOS_CONSEQUENCE_REQUIRED");
        }

        if (request.mode().ordinal() < IntelligenceMode.REASONING.ordinal()
                && GOVERNED_CONSEQUENCES.contains(consequence)) {
            throw new IllegalStateException("BIOS_GOVERNANCE_LEVEL_INSUFFICIENT");
        }

        if (request.mode().ordinal() >= IntelligenceMode.DECISION.ordinal()
                && isBlank(request.authorityContext())) {
            throw new IllegalStateException("BIOS_AUTHORITY_CONTEXT_REQUIRED");
        }

        if (HIGH_CONSEQUENCE.contains(consequence) && isBlank(request.authorityContext())) {
            throw new IllegalStateException("BIOS_HIGH_CONSEQUENCE_AUTHORITY_REQUIRED");
        }

        // Canonical Intelligence SOT: CRITICAL / IRREVERSIBLE work requires
        // multi-engine review plus BIOS and human authority. This validator is
        // the Workforce machine-checkable portion of that boundary.
        if (MULTI_ENGINE_REQUIRED.contains(consequence)) {
            if (request.collaborationMode() == CollaborationMode.SINGLE || request.maxProviders() < 2) {
                throw new IllegalStateException("BIOS_MULTI_ENGINE_REQUIRED");
            }
            if (isBlank(request.authorityContext())) {
                throw new IllegalStateException("BIOS_HUMAN_AUTHORITY_REQUIRED");
            }
        }
    }

    private void requireEvidence(List<String> references) {
        if (references.isEmpty()) {
            throw new IllegalStateException("BIOS_EVIDENCE_REQUIRED");
        }

        Set<String> normalized = new HashSet<>();
        for (String reference : references) {
            if (isBlank(reference)) throw new IllegalStateException("BIOS_EVIDENCE_REFERENCE_INVALID");
            String value = reference.trim();
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.equals("unknown") || lower.equals("none") || lower.equals("n/a") || lower.equals("no evidence")) {
                throw new IllegalStateException("BIOS_EVIDENCE_PLACEHOLDER_REJECTED");
            }
            if (!normalized.add(value)) {
                throw new IllegalStateException("BIOS_EVIDENCE_DUPLICATE");
            }
        }
    }

    private void validateResponses(IntelligenceRequest request, List<LlmResponse> responses) {
        if (responses.isEmpty()) throw new IllegalStateException("BIOS_NO_INTELLIGENCE_RESPONSE");

        Set<LlmProvider> providers = new HashSet<>();
        for (LlmResponse response : responses) {
            Objects.requireNonNull(response, "response");
            if (response.text() == null || response.text().isBlank()) {
                throw new IllegalStateException("BIOS_PROVIDER_OUTPUT_EMPTY");
            }
            if (response.provider() == null) {
                throw new IllegalStateException("BIOS_PROVIDER_ATTRIBUTION_MISSING");
            }
            if (!providers.add(response.provider())) {
                throw new IllegalStateException("BIOS_PROVIDER_DUPLICATE");
            }
        }

        String consequence = normalize(request.consequence());
        if (MULTI_ENGINE_REQUIRED.contains(consequence) && providers.size() < 2) {
            throw new IllegalStateException("BIOS_MULTI_ENGINE_RESPONSE_REQUIRED");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
