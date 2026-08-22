package com.metatron.workforce.phase5;

import java.util.Objects;

public record ResourceConstraint(
        String resourceId,
        double requiredQuantity,
        double usableQuantity,
        boolean authorized,
        boolean dependencySatisfied) {

    public ResourceConstraint {
        Objects.requireNonNull(resourceId, "resourceId");
        if (resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId must not be blank");
        }
        requireNonNegative("requiredQuantity", requiredQuantity);
        requireNonNegative("usableQuantity", usableQuantity);
    }

    public double deficit() {
        return Math.max(requiredQuantity - usableQuantity, 0d);
    }

    public boolean executable() {
        return authorized && dependencySatisfied && usableQuantity >= requiredQuantity;
    }

    private static void requireNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0d) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
