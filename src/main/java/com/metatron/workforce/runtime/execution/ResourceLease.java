package com.metatron.workforce.runtime.execution;

import java.time.Instant;
import java.util.Objects;

/** Durable protected-resource ownership; separate from ExecutionAttempt lease/fencing. */
public record ResourceLease(
        String leaseId,
        String claimId,
        String resourceId,
        String attemptId,
        String ownerActor,
        ResourceClaim.ResourceClass resourceClass,
        ResourceClaim.Mode mode,
        double quantity,
        long fencingToken,
        long stateVersion,
        Instant acquiredAt,
        Instant expiresAt,
        Instant heartbeatAt,
        Status status) {

    public enum Status { ACTIVE, RELEASED, EXPIRED, FENCED }

    public ResourceLease {
        leaseId = require(leaseId,"leaseId"); claimId=require(claimId,"claimId"); resourceId=require(resourceId,"resourceId");
        attemptId=require(attemptId,"attemptId"); ownerActor=require(ownerActor,"ownerActor"); Objects.requireNonNull(resourceClass,"resourceClass"); Objects.requireNonNull(mode,"mode");
        if(!Double.isFinite(quantity)||quantity<=0.0) throw new IllegalArgumentException("resource lease quantity must be positive finite");
        if(fencingToken<1||stateVersion<1) throw new IllegalArgumentException("fencing/state version must be positive");
        Objects.requireNonNull(acquiredAt); Objects.requireNonNull(expiresAt); Objects.requireNonNull(heartbeatAt); Objects.requireNonNull(status);
        if(!expiresAt.isAfter(acquiredAt)) throw new IllegalArgumentException("resource lease expiry must follow acquisition");
    }
    public boolean activeAt(Instant at){return status==Status.ACTIVE && expiresAt.isAfter(Objects.requireNonNull(at));}
    private static String require(String v,String f){if(v==null||v.isBlank())throw new IllegalArgumentException(f+" required");return v.trim();}
}
