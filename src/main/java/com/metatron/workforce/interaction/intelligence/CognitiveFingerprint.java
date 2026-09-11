package com.metatron.workforce.interaction.intelligence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Stable fingerprint utilities for reusable cognitive work. */
public final class CognitiveFingerprint {
    private CognitiveFingerprint() {}

    public static String sha256(
            String objective,
            String contextFingerprint,
            List<String> evidenceRefs,
            String capability,
            String outputContract) {
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(contextFingerprint, "contextFingerprint");
        Objects.requireNonNull(evidenceRefs, "evidenceRefs");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(outputContract, "outputContract");
        List<String> evidence = new ArrayList<>();
        for (String ref : evidenceRefs) {
            if (ref == null || ref.isBlank()) continue;
            String normalized = ref.trim();
            if (transportOnlyEvidence(normalized)) continue;
            evidence.add(normalized);
        }
        evidence.sort(String::compareTo);
        String source = "objective=" + normalize(objective)
                + "\ncontext=" + contextFingerprint.trim()
                + "\ncapability=" + normalize(capability)
                + "\noutput=" + normalize(outputContract)
                + "\nevidence=" + String.join("|", evidence);
        return digest(source);
    }

    /**
     * When the deterministic Institutional Context Resolver supplied a fingerprint, use that as the
     * stable cognition context identity instead of transport/history decoration. This keeps artifact
     * reuse channel-neutral while still invalidating on authority/runtime/context changes.
     */
    public static String contextFingerprint(String context) {
        Objects.requireNonNull(context, "context");
        for (String line : context.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("institutional_context_fingerprint=")) {
                String value = trimmed.substring("institutional_context_fingerprint=".length()).trim();
                if (!value.isBlank()) return value;
            }
        }
        return digest(context.trim());
    }

    private static boolean transportOnlyEvidence(String ref) {
        String value = ref.toLowerCase(java.util.Locale.ROOT);
        return value.startsWith("observation:telegram:")
                || value.startsWith("observation:web:")
                || value.startsWith("observation:api:")
                || value.startsWith("observation:zalo:")
                || value.startsWith("telegram:")
                || value.startsWith("web:")
                || value.startsWith("api:")
                || value.startsWith("zalo:");
    }

    private static String digest(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }
}
