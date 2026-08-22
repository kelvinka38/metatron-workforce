package com.metatron.workforce.phase7;

import com.metatron.workforce.phase5.CapacitySnapshot;
import com.metatron.workforce.phase5.StaffingSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Produces management read models from operational evidence without becoming an accounting authority. */
public final class ReportingService {

    public Variance variance(String metric, double planned, double actual) {
        return new Variance(metric, planned, actual);
    }

    public PerformanceSnapshot performance(
            String subjectId,
            double planned,
            double committed,
            double executed,
            double completed,
            double quality,
            double timeliness,
            double cost,
            double resourceEfficiency,
            double outcome) {
        return new PerformanceSnapshot(subjectId, planned, committed, executed, completed,
                quality, timeliness, cost, resourceEfficiency, outcome);
    }

    public ManagementDashboard dashboard(
            List<String> current,
            List<String> completed,
            List<String> blocked,
            CapacitySnapshot capacity,
            StaffingSnapshot staffing,
            PerformanceSnapshot performance,
            List<String> risks,
            List<String> exceptions,
            double reportedCost,
            Double budget,
            List<EconomicReportEvidence> economicEvidence,
            Map<String, Double> metrics) {
        Objects.requireNonNull(capacity, "capacity");
        Objects.requireNonNull(staffing, "staffing");
        Objects.requireNonNull(performance, "performance");
        return new ManagementDashboard(current, completed, blocked, capacity.remaining(), staffing.available,
                performance, risks, exceptions, reportedCost, budget, economicEvidence, metrics);
    }
}
