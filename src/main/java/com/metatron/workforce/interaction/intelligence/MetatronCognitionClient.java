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
            String requestReference) {
        public Response {
            text = require(text, "text");
            endpointId = require(endpointId, "endpointId");
            modelIdentity = modelIdentity == null ? "" : modelIdentity.trim();
            requestReference = requestReference == null ? "" : requestReference.trim();
            if (inputTokens < 0 || outputTokens < 0) throw new IllegalArgumentException("token usage must be non-negative");
        }
    }

    private static String require(String value, String field) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return cleaned;
    }
}
