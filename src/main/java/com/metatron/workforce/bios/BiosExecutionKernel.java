package com.metatron.workforce.bios;

import com.metatron.workforce.interaction.MetatronInteraction;
import com.metatron.workforce.interaction.MetatronInteractionOrchestrator;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Deterministic Workforce-side BIOS interaction conformance boundary.
 *
 * This kernel observes, normalizes, classifies, admits interaction semantics, and
 * verifies the downstream response. It does not originate institutional authority.
 * In particular, natural-language execution intent is never authorization proof.
 */
public final class BiosExecutionKernel {
    private static final Logger LOG = LoggerFactory.getLogger(BiosExecutionKernel.class);

    public MetatronInteractionOrchestrator.InteractionResponse execute(
            MetatronInteraction interaction,
            Function<MetatronInteraction, MetatronInteractionOrchestrator.InteractionResponse> downstream) {
        Objects.requireNonNull(interaction, "interaction");
        Objects.requireNonNull(downstream, "downstream");

        List<Phase> phases = new ArrayList<>();
        phases.add(Phase.OBSERVE);

        MetatronInteraction normalized = normalize(interaction);
        phases.add(Phase.NORMALIZE);

        IntelligenceMode mode = classify(normalized.text());
        phases.add(Phase.CLASSIFY);

        admit(normalized, mode);
        phases.add(Phase.ADMIT);

        LOG.info("bios_interaction_admission_pass request={} mode={} phases={}",
                normalized.externalMessageReference(), mode, phases);

        phases.add(Phase.HANDLE);
        MetatronInteractionOrchestrator.InteractionResponse response = downstream.apply(normalized);
        verify(normalized, mode, response);
        phases.add(Phase.VERIFY);

        LOG.info("bios_interaction_complete request={} mode={} phases={} provenance={}",
                normalized.externalMessageReference(), mode, phases, response.provenanceReference());
        return response;
    }

    public IntelligenceMode classify(String text) {
        Objects.requireNonNull(text, "text");
        String value = normalizeText(text).toLowerCase(Locale.ROOT);
        if (containsAny(value, "deploy", "execute", "ship it", "push to production", "run the fix", "fix it and deploy", "thực hiện ngay")) {
            return IntelligenceMode.EXECUTION;
        }
        if (containsAny(value, "decide", "approve", "authorize", "should we proceed", "make the decision")) {
            return IntelligenceMode.DECISION;
        }
        if (containsAny(value, "audit", "analyze", "analyse", "review", "diagnose", "compare", "investigate", "root cause", "why")) {
            return IntelligenceMode.REASONING;
        }
        return IntelligenceMode.DISCUSSION;
    }

    private MetatronInteraction normalize(MetatronInteraction interaction) {
        String text = normalizeText(interaction.text());
        if (text.isBlank()) throw new IllegalArgumentException("BIOS_INPUT_EMPTY");
        return new MetatronInteraction(
                interaction.human(),
                interaction.target(),
                interaction.organizationContextId().trim(),
                interaction.conversationId().trim(),
                interaction.channelProvider().trim(),
                interaction.externalActorReference().trim(),
                interaction.externalConversationReference().trim(),
                interaction.externalMessageReference().trim(),
                text);
    }

    private void admit(MetatronInteraction interaction, IntelligenceMode mode) {
        if (interaction.human().actorId().isBlank()) {
            throw new IllegalStateException("BIOS_HUMAN_IDENTITY_REQUIRED");
        }
        if (interaction.target().actorId().isBlank()) {
            throw new IllegalStateException("BIOS_TARGET_IDENTITY_REQUIRED");
        }
        if (interaction.organizationContextId().isBlank()) {
            throw new IllegalStateException("BIOS_AUTHORITY_CONTEXT_REQUIRED");
        }

        // EXECUTION INTENT != EXECUTION AUTHORIZATION.
        // BIOS admits the interaction semantics, not the material execution itself.
        // An EXECUTION-classified message must continue to the dedicated Workforce
        // execution-admission path, where real institutional authority, scope, policy,
        // assignment, validity and revocation evidence are evaluated. Allowing the
        // intent to reach that path does not grant authority and must never be treated
        // as authorization proof derived from the user's words.
        if (mode == IntelligenceMode.EXECUTION) {
            LOG.info("bios_execution_intent_requires_downstream_admission request={}",
                    interaction.externalMessageReference());
        }
    }

    private void verify(
            MetatronInteraction interaction,
            IntelligenceMode mode,
            MetatronInteractionOrchestrator.InteractionResponse response) {
        Objects.requireNonNull(response, "response");
        if (response.text() == null || response.text().isBlank()) {
            throw new IllegalStateException("BIOS_OUTPUT_EMPTY");
        }
        if (mode.ordinal() >= IntelligenceMode.DECISION.ordinal()
                && (response.provenanceReference() == null || response.provenanceReference().isBlank())) {
            throw new IllegalStateException("BIOS_PROVENANCE_REQUIRED");
        }
        if (normalizeText(response.text()).equalsIgnoreCase(normalizeText(interaction.text()))) {
            throw new IllegalStateException("BIOS_RESPONSE_ECHO");
        }
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private enum Phase {
        OBSERVE,
        NORMALIZE,
        CLASSIFY,
        ADMIT,
        HANDLE,
        VERIFY
    }
}
