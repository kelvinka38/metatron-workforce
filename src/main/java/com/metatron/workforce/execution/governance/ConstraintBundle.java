package com.metatron.workforce.execution.governance;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable compiled constraint set tied to one authority digest. */
public record ConstraintBundle(String bundleId, String authorityDigest, List<ConstraintBinding> constraints, String digest) {
    public ConstraintBundle {
        bundleId = require(bundleId, "bundleId");
        authorityDigest = require(authorityDigest, "authorityDigest");
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        digest = require(digest, "digest");
    }

    public static ConstraintBundle create(String authorityDigest, List<ConstraintBinding> bindings) {
        List<ConstraintBinding> ordered = bindings == null ? List.of() : bindings.stream()
                .sorted(Comparator.comparing(ConstraintBinding::constraintId)).toList();
        String material = authorityDigest + "\n" + ordered.stream().map(binding ->
                binding.constraintId() + "|" + binding.authorityArtifactIdentity() + "|" + binding.kind()
                        + "|" + binding.predicateType() + "|" + binding.predicatePayload()
                        + "|" + binding.reviewRequirement()).reduce("", (a, b) -> a + b + "\n");
        String digest = GovernanceDigests.sha256(material);
        return new ConstraintBundle("constraints:" + digest, authorityDigest, ordered, digest);
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
