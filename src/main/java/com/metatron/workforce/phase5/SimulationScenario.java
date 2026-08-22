package com.metatron.workforce.phase5;

import java.util.Map;
import java.util.Objects;

public record SimulationScenario(
        String scenarioId,
        String baselineId,
        Map<String, Double> assumptions,
        Map<String, Double> outputs) {

    public SimulationScenario {
        requireText("scenarioId", scenarioId);
        if (baselineId != null && baselineId.isBlank()) {
            throw new IllegalArgumentException("baselineId must not be blank");
        }
        Objects.requireNonNull(assumptions, "assumptions");
        Objects.requireNonNull(outputs, "outputs");
        assumptions = Map.copyOf(assumptions);
        outputs = Map.copyOf(outputs);
    }

    public boolean hasAssumption(String name) {
        return assumptions.containsKey(name);
    }

    private static void requireText(String name, String value) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
