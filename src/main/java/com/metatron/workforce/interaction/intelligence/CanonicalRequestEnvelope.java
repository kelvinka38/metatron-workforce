package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.MetatronInteraction;

import java.util.Objects;

/**
 * Provider-neutral, channel-neutral request boundary for the Cognitive Runtime.
 * Transport identifiers are correlation metadata only and never confer authority.
 */
public record CanonicalRequestEnvelope(
        String requestId,
        String requesterRef,
        String channel,
        String rawInput,
        String caseRef,
        String workerRef,
        ExplicitControls explicitControls,
        String authorityContextRef,
        String traceRef) {

    public CanonicalRequestEnvelope {
        requestId = required(requestId, "requestId");
        requesterRef = required(requesterRef, "requesterRef");
        channel = required(channel, "channel");
        rawInput = required(rawInput, "rawInput");
        caseRef = optional(caseRef);
        workerRef = optional(workerRef);
        explicitControls = explicitControls == null ? ExplicitControls.none() : explicitControls;
        authorityContextRef = optional(authorityContextRef);
        traceRef = required(traceRef, "traceRef");
    }

    public static CanonicalRequestEnvelope from(MetatronInteraction interaction) {
        Objects.requireNonNull(interaction, "interaction");
        String requestId = "interaction:" + interaction.channelProvider() + ":" + interaction.externalMessageReference();
        return new CanonicalRequestEnvelope(
                requestId,
                "human:" + interaction.human().actorId(),
                interaction.channelProvider(),
                interaction.text(),
                "",
                "worker:" + interaction.target().actorId(),
                ExplicitControls.none(),
                interaction.organizationContextId(),
                requestId);
    }

    public CanonicalRequestEnvelope withCaseRef(String value) {
        return new CanonicalRequestEnvelope(requestId, requesterRef, channel, rawInput, value, workerRef,
                explicitControls, authorityContextRef, traceRef);
    }

    public CanonicalRequestEnvelope withControls(ExplicitControls controls) {
        return new CanonicalRequestEnvelope(requestId, requesterRef, channel, rawInput, caseRef, workerRef,
                controls, authorityContextRef, traceRef);
    }

    public record ExplicitControls(
            IntelligenceDepth depth,
            String provider,
            String outputContract) {
        public ExplicitControls {
            provider = optional(provider);
            outputContract = optional(outputContract);
        }

        public static ExplicitControls none() {
            return new ExplicitControls(null, "", "");
        }
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }
}
