package com.metatron.workforce.phase7;

import java.util.List;
import java.util.Map;

/** Read model for management visibility; it does not grant authority. */
public record ManagementDashboard(
        List<String> currentWorkIds,
        List<String> completedWorkIds,
        List<String> blockedWorkIds,
        double remainingCapacity,
        int currentStaffing,
        PerformanceSnapshot performance,
        List<String> risks,
        List<String> exceptions,
        double reportedCost,
        Double budget,
        List<EconomicReportEvidence> economicEvidence,
        Map<String, Double> organizationalMetrics) {

    public ManagementDashboard {
        currentWorkIds = List.copyOf(currentWorkIds == null ? List.of() : currentWorkIds);
        completedWorkIds = List.copyOf(completedWorkIds == null ? List.of() : completedWorkIds);
        blockedWorkIds = List.copyOf(blockedWorkIds == null ? List.of() : blockedWorkIds);
        risks = List.copyOf(risks == null ? List.of() : risks);
        exceptions = List.copyOf(exceptions == null ? List.of() : exceptions);
        economicEvidence = List.copyOf(economicEvidence == null ? List.of() : economicEvidence);
        organizationalMetrics = Map.copyOf(organizationalMetrics == null ? Map.of() : organizationalMetrics);
        if (!Double.isFinite(remainingCapacity) || remainingCapacity < 0d) throw new IllegalArgumentException("remainingCapacity must be finite and non-negative");
        if (currentStaffing < 0) throw new IllegalArgumentException("currentStaffing must be non-negative");
        if (!Double.isFinite(reportedCost) || reportedCost < 0d) throw new IllegalArgumentException("reportedCost must be finite and non-negative");
        if (budget != null && (!Double.isFinite(budget) || budget < 0d)) throw new IllegalArgumentException("budget must be null or non-negative");
    }

    public boolean overBudget() { return budget != null && reportedCost > budget; }
    public boolean blocked() { return !blockedWorkIds.isEmpty(); }
}
