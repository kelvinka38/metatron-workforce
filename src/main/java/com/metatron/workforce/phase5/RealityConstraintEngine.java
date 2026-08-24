package com.metatron.workforce.phase5;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RealityConstraintEngine {

    public ExecutionFeasibility evaluate(
            double requiredLaborHours,
            CapacitySnapshot capacity,
            StaffingSnapshot staffing,
            List<ResourceConstraint> resources,
            boolean operatingWindowOpen,
            boolean budgetSufficient,
            boolean dependenciesSatisfied) {
        if (!Double.isFinite(requiredLaborHours) || requiredLaborHours < 0d) {
            throw new IllegalArgumentException("requiredLaborHours must be finite and non-negative");
        }
        Objects.requireNonNull(capacity, "capacity");
        Objects.requireNonNull(staffing, "staffing");
        Objects.requireNonNull(resources, "resources");

        List<String> blockers = new ArrayList<>();
        if (!operatingWindowOpen) blockers.add("operating-window-closed");
        if (!budgetSufficient) blockers.add("budget-insufficient");
        if (!dependenciesSatisfied) blockers.add("dependency-unsatisfied");

        double resourceDeficit = 0d;
        for (ResourceConstraint resource : resources) {
            if (!resource.authorized()) blockers.add("resource-unauthorized:" + resource.resourceId());
            if (!resource.dependencySatisfied()) blockers.add("resource-dependency-unsatisfied:" + resource.resourceId());
            resourceDeficit += resource.deficit();
        }

        double laborDeficit = Math.max(requiredLaborHours - capacity.available(), 0d);
        int qualifiedDeficit = staffing.qualifiedDeficit();
        boolean quantitativeDeficit = laborDeficit > 0d || qualifiedDeficit > 0 || resourceDeficit > 0d;

        if (!blockers.isEmpty()) return new ExecutionFeasibility(ExecutionFeasibility.Status.BLOCKED, laborDeficit, qualifiedDeficit, blockers);
        if (quantitativeDeficit) return new ExecutionFeasibility(ExecutionFeasibility.Status.PARTIAL, laborDeficit, qualifiedDeficit, List.of());
        return new ExecutionFeasibility(ExecutionFeasibility.Status.FEASIBLE, 0d, 0, List.of());
    }
}
