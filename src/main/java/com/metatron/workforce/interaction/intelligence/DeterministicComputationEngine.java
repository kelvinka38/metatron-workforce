package com.metatron.workforce.interaction.intelligence;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Performs exact/controlled arithmetic without frontier-model computation. */
public final class DeterministicComputationEngine {
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    public List<DeterministicComputationResult> execute(List<DeterministicComputationSpec> specs) {
        Objects.requireNonNull(specs, "specs");
        List<DeterministicComputationResult> results = new ArrayList<>();
        for (DeterministicComputationSpec spec : specs) results.add(execute(spec));
        return List.copyOf(results);
    }

    public DeterministicComputationResult execute(DeterministicComputationSpec spec) {
        Objects.requireNonNull(spec, "spec");
        List<BigDecimal> operands = spec.operands().stream().map(DeterministicComputationEngine::decimal).toList();
        validateArity(spec.operation(), operands.size());

        BigDecimal value = switch (spec.operation()) {
            case SUM -> operands.stream().reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MC));
            case AVERAGE -> operands.stream().reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MC))
                    .divide(BigDecimal.valueOf(operands.size()), MC);
            case DIFFERENCE -> operands.get(0).subtract(operands.get(1), MC);
            case PRODUCT -> operands.stream().reduce(BigDecimal.ONE, (a, b) -> a.multiply(b, MC));
            case DIVIDE -> divide(operands.get(0), operands.get(1));
            case PERCENT_OF -> divide(operands.get(0), operands.get(1)).multiply(BigDecimal.valueOf(100), MC);
            case PERCENT_CHANGE -> divide(operands.get(0).subtract(operands.get(1), MC), operands.get(1))
                    .multiply(BigDecimal.valueOf(100), MC);
        };

        String expression = expression(spec.operation(), spec.operands());
        String unit = switch (spec.operation()) {
            case PERCENT_OF, PERCENT_CHANGE -> "%";
            default -> spec.unit();
        };
        return new DeterministicComputationResult(spec.label(), spec.operation(), normalize(value), unit, expression);
    }

    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.compareTo(BigDecimal.ZERO) == 0) throw new IllegalArgumentException("division by zero");
        return numerator.divide(denominator, MC);
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("numeric operand must not be blank");
        try {
            return new BigDecimal(value.trim(), MC);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("invalid numeric operand: " + value, failure);
        }
    }

    private static void validateArity(DeterministicComputationOperation operation, int size) {
        switch (operation) {
            case SUM, AVERAGE -> {
                if (size < 1) throw new IllegalArgumentException(operation + " requires at least one operand");
            }
            case PRODUCT -> {
                if (size < 2) throw new IllegalArgumentException("PRODUCT requires at least two operands");
            }
            case DIFFERENCE, DIVIDE, PERCENT_OF, PERCENT_CHANGE -> {
                if (size != 2) throw new IllegalArgumentException(operation + " requires exactly two operands");
            }
        }
    }

    private static BigDecimal normalize(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static String expression(DeterministicComputationOperation operation, List<String> operands) {
        return switch (operation) {
            case SUM -> String.join(" + ", operands);
            case AVERAGE -> "avg(" + String.join(", ", operands) + ")";
            case DIFFERENCE -> operands.get(0) + " - " + operands.get(1);
            case PRODUCT -> String.join(" × ", operands);
            case DIVIDE -> operands.get(0) + " / " + operands.get(1);
            case PERCENT_OF -> "(" + operands.get(0) + " / " + operands.get(1) + ") × 100";
            case PERCENT_CHANGE -> "((" + operands.get(0) + " - " + operands.get(1) + ") / " + operands.get(1) + ") × 100";
        };
    }
}
