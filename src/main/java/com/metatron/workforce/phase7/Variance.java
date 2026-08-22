package com.metatron.workforce.phase7;

public record Variance(String metric, double planned, double actual) {
    public Variance {
        if (metric == null || metric.isBlank()) throw new IllegalArgumentException("metric must not be blank");
        if (!Double.isFinite(planned) || !Double.isFinite(actual)) throw new IllegalArgumentException("planned and actual must be finite");
    }
    public double value() { return actual - planned; }
}
