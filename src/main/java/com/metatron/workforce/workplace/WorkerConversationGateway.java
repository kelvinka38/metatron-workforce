package com.metatron.workforce.workplace;

import java.util.List;
import java.util.Objects;

/** Worker-bound live conversation boundary used by Meeting. */
@FunctionalInterface
public interface WorkerConversationGateway {
    Reply converse(String workerId, String role, String userMessage, String conversationContext);

    record Reply(String text, String requestReference, List<String> evidenceReferences) {
        public Reply {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(requestReference, "requestReference");
            Objects.requireNonNull(evidenceReferences, "evidenceReferences");
            evidenceReferences = List.copyOf(evidenceReferences);
            if (text.isBlank()) throw new IllegalArgumentException("worker conversation reply must not be blank");
        }
    }
}
