package com.metatron.workforce.management;

import java.time.Instant;
import java.util.Objects;

/** Durable management lease carrying a monotonically increasing fencing version. */
public record ManagementLease(
        String objectiveId,
        String runnerId,
        String token,
        long fencingVersion,
        Instant acquiredAt,
        Instant expiresAt) {

    public ManagementLease {
        requireText(objectiveId, "objectiveId");
        requireText(runnerId, "runnerId");
        requireText(token, "token");
        if (fencingVersion < 1) throw new IllegalArgumentException("fencingVersion must be positive");
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (expiresAt.isBefore(acquiredAt)) throw new IllegalArgumentException("lease expiry must not precede acquisition");
    }

    public boolean activeAt(Instant at) {
        return expiresAt.isAfter(Objects.requireNonNull(at, "at"));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
