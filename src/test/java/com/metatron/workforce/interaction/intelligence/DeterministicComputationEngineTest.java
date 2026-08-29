package com.metatron.workforce.interaction.intelligence;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class DeterministicComputationEngineTest {
    private final DeterministicComputationEngine engine = new DeterministicComputationEngine();

    @Test
    void calculatesPercentChangeExactlyWithoutFrontierReasoning() {
        DeterministicComputationResult result = engine.execute(new DeterministicComputationSpec(
                "GMV growth", DeterministicComputationOperation.PERCENT_CHANGE,
                List.of("120", "100"), "%"));

        assertEquals(new BigDecimal("20"), result.value());
        assertEquals("%", result.unit());
        assertEquals("((120 - 100) / 100) × 100", result.expression());
    }

    @Test
    void calculatesAverageAndPreservesUnit() {
        DeterministicComputationResult result = engine.execute(new DeterministicComputationSpec(
                "AOV", DeterministicComputationOperation.AVERAGE,
                List.of("10", "20", "30"), "VND"));

        assertEquals(new BigDecimal("20"), result.value());
        assertEquals("VND", result.unit());
    }

    @Test
    void failsClosedOnDivisionByZero() {
        assertThrows(IllegalArgumentException.class, () -> engine.execute(new DeterministicComputationSpec(
                "ratio", DeterministicComputationOperation.DIVIDE,
                List.of("10", "0"), "")));
    }
}
