package com.metatron.workforce.phase5;

import java.time.Instant;

public record StaffingRequest(
        String staffingRequestId,
        String objectiveRef,
        String organizationContextId,
        String requestedByWorkerId,
        String capabilityRef,
        double requiredCapacity,
        double availableCapacity,
        Status status,
        String resolutionRef,
        Instant createdAt,
        Instant updatedAt) {
    public enum Status { OPEN, PROPOSED, RESOLVED, CANCELLED, ESCALATED }
    public StaffingRequest {
        require(staffingRequestId,"staffingRequestId"); require(objectiveRef,"objectiveRef"); require(organizationContextId,"organizationContextId");
        require(requestedByWorkerId,"requestedByWorkerId"); require(capabilityRef,"capabilityRef");
        if (!Double.isFinite(requiredCapacity) || !Double.isFinite(availableCapacity) || requiredCapacity < 0 || availableCapacity < 0)
            throw new IllegalArgumentException("capacity values invalid");
        if (status == null || createdAt == null || updatedAt == null) throw new IllegalArgumentException("status/timestamps required");
    }
    public double gap() { return Math.max(requiredCapacity - availableCapacity, 0d); }
    private static void require(String v,String n){ if(v==null||v.isBlank()) throw new IllegalArgumentException(n+" required"); }
}
