package com.metatron.workforce.phase5;

public record CapacitySnapshot(
        double scheduled,
        double unavailable,
        double committed,
        double required) {

    public CapacitySnapshot {
        requireNonNegative("scheduled", scheduled);
        requireNonNegative("unavailable", unavailable);
        requireNonNegative("committed", committed);
        requireNonNegative("required", required);
        if (unavailable > scheduled) {
            throw new IllegalArgumentException("unavailable cannot exceed scheduled");
        }
    }

    public double available() {
        return scheduled - unavailable;
    }

    public double remaining() {
        return Math.max(available() - committed, 0d);
    }

    public double deficit() {
        return Math.max(required - available(), 0d);
    }

    public boolean overcommitted() {
        return committed > available();
    }

    public double utilization(double consumed) {
        requireNonNegative("consumed", consumed);
        return available() == 0d ? (consumed == 0d ? 0d : Double.POSITIVE_INFINITY) : consumed / available();
    }

    public double coverageRatio() {
        return required == 0d ? 1d : available() / required;
    }

    private static void requireNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0d) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
