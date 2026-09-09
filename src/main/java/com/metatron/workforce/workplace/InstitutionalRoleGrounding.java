package com.metatron.workforce.workplace;

import java.util.List;
import java.util.Objects;

/**
 * Resolves a Worker's institutional role to canonical institutional sources before cognition.
 * Grounding is evidence/context only; it does not create authority or execute work.
 */
@FunctionalInterface
public interface InstitutionalRoleGrounding {
    Grounding resolve(String roleRef, String positionRef, String requestedRole, String userMessage);

    record Grounding(boolean available, String domain, String context, List<String> evidenceReferences, String reason) {
        public Grounding {
            domain = domain == null ? "" : domain.trim();
            context = context == null ? "" : context;
            evidenceReferences = List.copyOf(Objects.requireNonNull(evidenceReferences, "evidenceReferences"));
            reason = reason == null ? "" : reason.trim();
            if (available && (domain.isBlank() || context.isBlank() || evidenceReferences.isEmpty())) {
                throw new IllegalArgumentException("available institutional grounding requires domain, context and evidence");
            }
        }

        public static Grounding available(String domain, String context, List<String> evidenceReferences) {
            return new Grounding(true, domain, context, evidenceReferences, "");
        }

        public static Grounding unavailable(String reason) {
            return new Grounding(false, "", "", List.of("institutional-grounding:unavailable:" + safe(reason)), reason);
        }

        private static String safe(String value) {
            if (value == null || value.isBlank()) return "unknown";
            return value.replaceAll("[^A-Za-z0-9._:-]+", "-");
        }
    }
}
