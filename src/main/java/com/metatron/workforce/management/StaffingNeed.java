package com.metatron.workforce.management;

import java.time.Instant;

/** Explicit Workforce-side staffing demand derived from a capability/capacity gap. */
public record StaffingNeed(
        String objectiveId,
        String managerWorkerId,
        String organizationContextId,
        String requiredCapability,
        double requiredCapacity,
        double availableCapacity,
        double capacityGap,
        Instant detectedAt) {

    public StaffingNeed {
        if (objectiveId == null || objectiveId.isBlank()) throw new IllegalArgumentException("objectiveId must not be blank");
        if (managerWorkerId == null || managerWorkerId.isBlank()) throw new IllegalArgumentException("managerWorkerId must not be blank");
        if (organizationContextId == null || organizationContextId.isBlank()) throw new IllegalArgumentException("organizationContextId must not be blank");
        if (requiredCapability == null || requiredCapability.isBlank()) throw new IllegalArgumentException("requiredCapability must not be blank");
        if (requiredCapacity < 0 || availableCapacity < 0) throw new IllegalArgumentException("capacity values must be >= 0");
        if (capacityGap <= 0) throw new IllegalArgumentException("capacityGap must be > 0");
        if (detectedAt == null) throw new NullPointerException("detectedAt");
    }
}
