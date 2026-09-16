package com.metatron.workforce.interaction.intelligence;

import java.util.List;
import java.util.Objects;

/** Private Metatron-owned inference substrate contract. */
@FunctionalInterface
public interface MetatronCognitionClient {
    Response reason(Request request);

    record Request(
            String requestId,
            String capability,
            String objective,
            String context,
            List<String> evidenceReferences,
            IntelligenceOriginContext origin,
            String requiredOutput) {
        public Request {
            requestId = require(requestId, "requestId");
            capability = require(capability, "capability");
            objective = require(objective, "objective");
            context = context == null ? "" : context;
            evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
            Objects.requireNonNull(origin, "origin");
            requiredOutput = require(requiredOutput, "requiredOutput");
        }
    }

    record Response(
            String text,
            String endpointId,
            String modelIdentity,
            long inputTokens,
            long outputTokens,
            String requestReference,
            String provider,
            long latencyMillis,
            boolean fallbackOccurred,
            List<String> providerAttempts) {
        /** Compatibility constructor for cognition transports without Phase 3 execution evidence. */
        public Response(String text, String endpointId, String modelIdentity,
                        long inputTokens, long outputTokens, String requestReference) {
            this(text, endpointId, modelIdentity, inputTokens, outputTokens, requestReference,
                    endpointId, 0L, false, List.of());
        }

        public Response {
            text = require(text, "text");
            endpointId = require(endpointId, "endpointId");
            modelIdentity = modelIdentity == null ? "" : modelIdentity.trim();
            requestReference = requestReference == null ? "" : requestReference.trim();
            provider = provider == null ? "" : provider.trim();
            providerAttempts = providerAttempts == null ? List.of() : List.copyOf(providerAttempts);
            if (inputTokens < 0 || outputTokens < 0 || latencyMillis < 0) {
                throw new IllegalArgumentException("cognition usage values must be non-negative");
            }
        }
    }

    private static String require(String value, String field) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return cleaned;
    }
}
