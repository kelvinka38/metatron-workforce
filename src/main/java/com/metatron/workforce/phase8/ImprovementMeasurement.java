package com.metatron.workforce.phase8;

public record ImprovementMeasurement(
        String metric,
        double baseline,
        double newPerformance,
        double improvement) {

    public ImprovementMeasurement {
        if (!Double.isFinite(baseline)
                || !Double.isFinite(newPerformance)
                || !Double.isFinite(improvement)) {
            throw new IllegalArgumentException("Performance values must be finite.");
        }
    }

    public static ImprovementMeasurement measure(
            PerformanceBaseline baseline,
            double newPerformance) {

        if (!Double.isFinite(newPerformance)) {
            throw new IllegalArgumentException("New performance must be finite.");
        }

        double improvement =
                baseline.direction().equals("HIGHER_IS_BETTER")
                        ? newPerformance - baseline.value()
                        : baseline.value() - newPerformance;

        return new ImprovementMeasurement(
                baseline.metric(),
                baseline.value(),
                newPerformance,
                improvement);
    }

    public boolean improved() {
        return improvement > 0;
    }
}
