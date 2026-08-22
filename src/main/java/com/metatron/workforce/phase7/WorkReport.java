package com.metatron.workforce.phase7;

import java.time.Instant;
import java.util.Objects;

/** Attributable operational report produced by a Worker. */
public record WorkReport(
        String reportId,
        String workerId,
        String organizationContextId,
        ReportType type,
        Instant periodStart,
        Instant periodEnd,
        String statusSummary,
        String evidenceReference,
        Instant reportedAt) {

    public enum ReportType { DAILY, PERIODIC, TASK, EXCEPTION, INCIDENT, PERFORMANCE, ECONOMIC }

    public WorkReport {
        requireText(reportId, "reportId");
        requireText(workerId, "workerId");
        requireText(organizationContextId, "organizationContextId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        if (!periodEnd.isAfter(periodStart)) throw new IllegalArgumentException("periodEnd must be after periodStart");
        requireText(statusSummary, "statusSummary");
        requireText(evidenceReference, "evidenceReference");
        Objects.requireNonNull(reportedAt, "reportedAt");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
