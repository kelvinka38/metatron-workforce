package com.metatron.workforce.phase5;

public record StaffingSnapshot(
        int required,
        int current,
        int available,
        int qualified) {

    public StaffingSnapshot {
        requireNonNegative("required", required);
        requireNonNegative("current", current);
        requireNonNegative("available", available);
        requireNonNegative("qualified", qualified);
        if (available > current) {
            throw new IllegalArgumentException("available cannot exceed current");
        }
        if (qualified > available) {
            throw new IllegalArgumentException("qualified cannot exceed available");
        }
    }

    public int deficit() {
        return Math.max(required - available, 0);
    }

    public int qualifiedDeficit() {
        return Math.max(required - qualified, 0);
    }

    public double coverageRatio() {
        return required == 0 ? 1d : (double) qualified / required;
    }

    private static void requireNonNegative(String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
