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
 * Deterministic Workforce-side BIOS execution boundary.
 *
 * BIOS is the control path around execution, not an LLM prompt. Every canonical
 * interaction is observed, normalized, classified, admitted, executed, and verified
 * here before the downstream handler is allowed to produce an answer or action.
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

        LOG.info("bios_admission_pass request={} mode={} phases={}",
                normalized.externalMessageReference(), mode, phases);

        phases.add(Phase.EXECUTE);
        MetatronInteractionOrchestrator.InteractionResponse response = downstream.apply(normalized);
        verify(normalized, mode, response);
        phases.add(Phase.VERIFY);

        LOG.info("bios_execution_complete request={} mode={} phases={} provenance={}",
                normalized.externalMessageReference(), mode, phases, response.provenanceReference());
        return response;
    }

    public IntelligenceMode classify(String text) {
        Objects.requireNonNull(text, "text");
        String value = normalizeText(text).toLowerCase(Locale.ROOT);
        if (containsAny(value, "deploy", "execute", "ship it", "push to production", "run the fix", "fix it and deploy")) {
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

        // A direct Telegram/user instruction is not, by itself, proof of execution
        // authority. The existing identity resolver establishes the authenticated
        // human + organization boundary; execution remains gated on an explicit
        // authorization phrase until a richer authority source is connected.
        if (mode == IntelligenceMode.EXECUTION && !hasExplicitAuthorization(interaction.text())) {
            throw new IllegalStateException("BIOS_EXECUTION_AUTHORIZATION_REQUIRED");
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

    private static boolean hasExplicitAuthorization(String text) {
        String value = normalizeText(text).toLowerCase(Locale.ROOT);
        return containsAny(value,
                "i authorize", "i approve", "authorized", "approved",
                "đồng ý thực hiện", "cho phép thực hiện", "được phép thực hiện",
                "ủy quyền thực hiện", "uy quyen thuc hien", "thực hiện ngay");
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
        EXECUTE,
        VERIFY
    }
}
