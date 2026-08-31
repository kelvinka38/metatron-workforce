package com.metatron.workforce.management;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Workforce operational safety projection for one Objective.
 * Authority/accounting truth remain external; this state only enforces delegated ceilings and controls.
 */
public record AutonomySafetyState(
        String objectiveId,
        ControlStatus controlStatus,
        boolean authorityRevoked,
        String revokedAuthorityReference,
        double maxCostUnits,
        double consumedCostUnits,
        int maxDispatchAttempts,
        int consumedDispatchAttempts,
        Instant deadline,
        RiskLevel maxRisk,
        Map<String, DispatchCharge> dispatchCharges,
        List<Amendment> amendments,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum ControlStatus { RUNNING, PAUSED, CANCELLED }
    public enum RiskLevel { LOW, MEDIUM, HIGH }

    public AutonomySafetyState {
        requireText(objectiveId, "objectiveId");
        Objects.requireNonNull(controlStatus, "controlStatus");
        revokedAuthorityReference = clean(revokedAuthorityReference);
        if (maxCostUnits <= 0 || !Double.isFinite(maxCostUnits)) throw new IllegalArgumentException("maxCostUnits must be finite and positive");
        if (consumedCostUnits < 0 || !Double.isFinite(consumedCostUnits)) throw new IllegalArgumentException("consumedCostUnits must be finite and >= 0");
        if (maxDispatchAttempts < 1) throw new IllegalArgumentException("maxDispatchAttempts must be positive");
        if (consumedDispatchAttempts < 0) throw new IllegalArgumentException("consumedDispatchAttempts must be >= 0");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(maxRisk, "maxRisk");
        dispatchCharges = Map.copyOf(Objects.requireNonNull(dispatchCharges, "dispatchCharges"));
        amendments = List.copyOf(Objects.requireNonNull(amendments, "amendments"));
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public record DispatchCharge(String dispatchReference, double costUnits, int attempt, Instant chargedAt) {
        public DispatchCharge {
            requireText(dispatchReference, "dispatchReference");
            if (costUnits <= 0 || !Double.isFinite(costUnits)) throw new IllegalArgumentException("costUnits must be finite and positive");
            if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
            Objects.requireNonNull(chargedAt, "chargedAt");
        }
    }

    public record Amendment(String amendmentReference, String detail, String authorityReference, Instant recordedAt) {
        public Amendment {
            requireText(amendmentReference, "amendmentReference");
            requireText(detail, "detail");
            requireText(authorityReference, "authorityReference");
            Objects.requireNonNull(recordedAt, "recordedAt");
        }
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
