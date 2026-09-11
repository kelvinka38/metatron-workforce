package com.metatron.workforce.interaction.intelligence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, attributable institutional context supplied before frontier cognition.
 * References remain references; this object is not itself authority.
 */
public record InstitutionalContextPackage(
        String contextId,
        List<String> authorityChain,
        List<String> canonicalRefs,
        List<String> planRefs,
        List<String> runtimeRefs,
        List<String> caseRefs,
        List<String> evidenceRefs,
        List<String> conflicts,
        Map<String, String> freshness,
        String fingerprint,
        Instant resolvedAt) {

    public InstitutionalContextPackage {
        contextId = required(contextId, "contextId");
        authorityChain = normalized(authorityChain);
        canonicalRefs = normalized(canonicalRefs);
        planRefs = normalized(planRefs);
        runtimeRefs = normalized(runtimeRefs);
        caseRefs = normalized(caseRefs);
        evidenceRefs = normalized(evidenceRefs);
        conflicts = normalized(conflicts);
        freshness = freshness == null ? Map.of() : Map.copyOf(freshness);
        fingerprint = required(fingerprint, "fingerprint");
        resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
    }

    public static InstitutionalContextPackage resolve(
            String contextId,
            List<String> authorityChain,
            List<String> canonicalRefs,
            List<String> planRefs,
            List<String> runtimeRefs,
            List<String> caseRefs,
            List<String> evidenceRefs,
            List<String> conflicts,
            Map<String, String> freshness) {
        List<String> normalizedAuthority = normalized(authorityChain);
        List<String> normalizedCanonical = normalized(canonicalRefs);
        List<String> normalizedPlan = normalized(planRefs);
        List<String> normalizedRuntime = normalized(runtimeRefs);
        List<String> normalizedCase = normalized(caseRefs);
        List<String> normalizedEvidence = normalized(evidenceRefs);
        List<String> normalizedConflicts = normalized(conflicts);
        Map<String, String> normalizedFreshness = freshness == null ? Map.of() : Map.copyOf(freshness);
        String fingerprint = fingerprint(normalizedAuthority, normalizedCanonical, normalizedPlan,
                normalizedRuntime, normalizedCase, normalizedEvidence, normalizedConflicts, normalizedFreshness);
        return new InstitutionalContextPackage(contextId, normalizedAuthority, normalizedCanonical,
                normalizedPlan, normalizedRuntime, normalizedCase, normalizedEvidence,
                normalizedConflicts, normalizedFreshness, fingerprint, Instant.now());
    }

    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }

    private static String fingerprint(
            List<String> authority,
            List<String> canonical,
            List<String> plans,
            List<String> runtime,
            List<String> cases,
            List<String> evidence,
            List<String> conflicts,
            Map<String, String> freshness) {
        StringBuilder source = new StringBuilder();
        append(source, "authority", authority);
        append(source, "canonical", canonical);
        append(source, "plans", plans);
        append(source, "runtime", runtime);
        append(source, "cases", cases);
        append(source, "evidence", evidence);
        append(source, "conflicts", conflicts);
        freshness.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> source.append("freshness:").append(entry.getKey())
                        .append('=').append(entry.getValue()).append('\n'));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void append(StringBuilder out, String label, List<String> values) {
        values.stream().sorted().forEach(value -> out.append(label).append(':').append(value).append('\n'));
    }

    private static List<String> normalized(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null) continue;
            String trimmed = value.trim();
            if (!trimmed.isBlank() && !result.contains(trimmed)) result.add(trimmed);
        }
        return List.copyOf(result);
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
