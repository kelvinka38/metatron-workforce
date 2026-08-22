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
        Instant reportedAt,
        StatementNature statementNature) {

    public enum ReportType { DAILY, PERIODIC, TASK, EXCEPTION, INCIDENT, PERFORMANCE, ECONOMIC }

    /** Distinguishes directly observed statements from model-derived values. */
    public enum StatementNature { OBSERVED, INFERRED, ESTIMATED }

    /** Backward-compatible constructor for existing reports whose nature is observed evidence. */
    public WorkReport(
            String reportId,
            String workerId,
            String organizationContextId,
            ReportType type,
            Instant periodStart,
            Instant periodEnd,
            String statusSummary,
            String evidenceReference,
            Instant reportedAt) {
        this(reportId, workerId, organizationContextId, type, periodStart, periodEnd,
                statusSummary, evidenceReference, reportedAt, StatementNature.OBSERVED);
    }

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
        Objects.requireNonNull(statementNature, "statementNature");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
