package com.metatron.workforce.runtime.execution;

import java.util.Map;
import java.util.Objects;

/** One infrastructure/shared-resource claim owned by an existing ExecutionAttempt. */
public record ResourceClaim(
        String claimId,
        String attemptId,
        String resourceId,
        ResourceClass resourceClass,
        Mode mode,
        double quantity,
        String unit,
        boolean required,
        String expectedVersion,
        Map<String,String> metadata) {

    public enum ResourceClass { COMPUTE, REPOSITORY, BUILD, EXTERNAL, INTEGRATION, ENVIRONMENT }
    public enum Mode { READ_SHARED, WRITE_EXCLUSIVE, LEASE_EXCLUSIVE, CAS_SERIALIZED, CAPACITY }

    public ResourceClaim {
        claimId = require(claimId, "claimId");
        attemptId = require(attemptId, "attemptId");
        resourceId = require(resourceId, "resourceId");
        Objects.requireNonNull(resourceClass, "resourceClass");
        Objects.requireNonNull(mode, "mode");
        if (!Double.isFinite(quantity) || quantity <= 0.0) throw new IllegalArgumentException("quantity must be positive finite");
        unit = unit == null ? "unit" : unit.trim();
        if (unit.isEmpty()) unit = "unit";
        expectedVersion = expectedVersion == null ? "" : expectedVersion.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (metadata.size() > 32) throw new IllegalArgumentException("resource claim metadata too large");
        metadata.forEach((k,v) -> {
            if (k == null || k.isBlank() || k.length() > 120 || v == null || v.length() > 500) {
                throw new IllegalArgumentException("invalid resource claim metadata");
            }
        });
    }

    public boolean exclusive() { return mode == Mode.WRITE_EXCLUSIVE || mode == Mode.LEASE_EXCLUSIVE || mode == Mode.CAS_SERIALIZED; }
    public boolean capacity() { return mode == Mode.CAPACITY; }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        String v = value.trim();
        if (v.length() > 300 || v.indexOf('\0') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) throw new IllegalArgumentException("invalid " + field);
        return v;
    }
}
