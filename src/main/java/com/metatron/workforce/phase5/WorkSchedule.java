package com.metatron.workforce.phase5;

import java.time.Instant;

public record WorkSchedule(
        String scheduleId,
        String assignmentRef,
        String workerId,
        Instant start,
        Instant end,
        double committedCapacity,
        Status status,
        String evidenceRef) {
    public enum Status { PLANNED, ACTIVE, COMPLETED, CANCELLED }
    public WorkSchedule {
        require(scheduleId, "scheduleId"); require(assignmentRef, "assignmentRef"); require(workerId, "workerId"); require(evidenceRef, "evidenceRef");
        if (start == null || end == null || !end.isAfter(start)) throw new IllegalArgumentException("valid schedule window required");
        if (!Double.isFinite(committedCapacity) || committedCapacity < 0) throw new IllegalArgumentException("committedCapacity must be finite and non-negative");
        if (status == null) throw new IllegalArgumentException("status required");
    }
    public boolean activeAt(Instant at) { return status != Status.CANCELLED && !at.isBefore(start) && at.isBefore(end); }
    private static void require(String v, String n) { if (v == null || v.isBlank()) throw new IllegalArgumentException(n + " required"); }
}
