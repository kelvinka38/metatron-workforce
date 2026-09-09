package com.metatron.workforce.workplace;

import java.util.List;
import java.util.Objects;

/** Channel-neutral live conversation boundary bound to one real canonical Worker. */
@FunctionalInterface
public interface WorkerConversationGateway {
    Reply converse(String workerId, String role, String userMessage, String conversationContext);

    default Reply converse(String workerId, String role, String userMessage, String conversationContext,
                           List<String> trustedExecutionEvidence) {
        return converse(workerId, role, userMessage, conversationContext);
    }

    record Reply(String text, String requestReference, List<String> evidenceReferences, String runtimeId) {
        public Reply(String text, String requestReference, List<String> evidenceReferences) {
            this(text, requestReference, evidenceReferences, "");
        }

        public Reply {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(requestReference, "requestReference");
            Objects.requireNonNull(evidenceReferences, "evidenceReferences");
            runtimeId = runtimeId == null ? "" : runtimeId;
            evidenceReferences = List.copyOf(evidenceReferences);
            if (text.isBlank()) throw new IllegalArgumentException("worker conversation reply must not be blank");
        }
    }
}
