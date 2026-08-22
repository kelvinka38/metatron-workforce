package com.metatron.workforce.phase10;

import java.time.Instant;
import java.util.Objects;

/**
 * End-to-end deterministic vertical slice for a realistic farm workforce.
 * External domains remain authoritative; this service only coordinates the slice.
 */
public final class Phase10PrimaryVerticalSliceService {

    public FarmOperatingPlan plan(String planId, String farmId, int requiredLaborHours, int workerCount,
            int shiftHoursPerWorker, double hourlyLaborCost, double resourceCost, double expectedOutput,
            String schedule, String risks, String provenance) {
        Objects.requireNonNull(planId);
        Objects.requireNonNull(farmId);
        Objects.requireNonNull(schedule);
        Objects.requireNonNull(risks);
        Objects.requireNonNull(provenance);
        if (requiredLaborHours < 0 || workerCount < 0 || shiftHoursPerWorker < 0
                || hourlyLaborCost < 0 || resourceCost < 0 || expectedOutput < 0) {
            throw new IllegalArgumentException("plan inputs cannot be negative");
        }
        int available = workerCount * shiftHoursPerWorker;
        int deficit = Math.max(0, requiredLaborHours - available);
        double laborCost = available * hourlyLaborCost;
        return new FarmOperatingPlan(planId, farmId, requiredLaborHours, available, deficit,
                workerCount, shiftHoursPerWorker, laborCost, resourceCost, expectedOutput,
                schedule, risks, provenance);
    }

    public Approval approve(FarmOperatingPlan plan, String authorityReference, String provenance) {
        Objects.requireNonNull(plan);
        if (authorityReference == null || authorityReference.isBlank()) {
            throw new IllegalArgumentException("explicit authority reference is required");
        }
        if (provenance == null || provenance.isBlank()) {
            throw new IllegalArgumentException("approval provenance is required");
        }
        return new Approval("approval-" + plan.planId(), Approval.Status.APPROVED,
                authorityReference, Instant.now(), provenance);
    }

    public Approval reject(FarmOperatingPlan plan, String authorityReference, String provenance) {
        Objects.requireNonNull(plan);
        return new Approval("approval-" + plan.planId(), Approval.Status.REJECTED,
                Objects.requireNonNull(authorityReference), Instant.now(), Objects.requireNonNull(provenance));
    }

    public ExecutionOutcome execute(FarmOperatingPlan plan, Approval approval, int actualLaborHours,
            double actualCost, double actualOutput, String provenance) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(approval);
        Objects.requireNonNull(provenance);
        if (approval.status() != Approval.Status.APPROVED) {
            return new ExecutionOutcome("execution-" + plan.planId(), false, 0, 0, 0,
                    "execution denied", "authorization was not approved", provenance);
        }
        if (plan.capacityDeficitHours() > 0) {
            return new ExecutionOutcome("execution-" + plan.planId(), false, 0, 0, 0,
                    "execution blocked", "capacity deficit: " + plan.capacityDeficitHours() + " labor-hours", provenance);
        }
        if (actualLaborHours < 0 || actualCost < 0 || actualOutput < 0) {
            throw new IllegalArgumentException("execution values cannot be negative");
        }
        if (actualLaborHours < plan.requiredLaborHours()) {
            return new ExecutionOutcome("execution-" + plan.planId(), false, actualLaborHours,
                    actualCost, actualOutput, "execution incomplete",
                    "execution stopped before required labor-hours were completed", provenance);
        }
        return new ExecutionOutcome("execution-" + plan.planId(), true, actualLaborHours,
                actualCost, actualOutput, "execution completed", "", provenance);
    }

    public CycleReport report(FarmOperatingPlan plan, ExecutionOutcome execution, String provenance) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(execution);
        double outputVariance = execution.actualOutput() - plan.expectedOutput();
        double plannedCost = plan.plannedLaborCost() + plan.plannedResourceCost();
        double costVariance = execution.actualCost() - plannedCost;
        int laborVariance = execution.actualLaborHours() - plan.requiredLaborHours();
        String performance = execution.success()
                ? (outputVariance >= 0 ? "TARGET_MET" : "TARGET_MISSED")
                : "EXECUTION_FAILED";
        String issues = execution.success() ? "" : execution.failure();
        return new CycleReport("report-" + plan.planId(), plan.expectedOutput(), execution.actualOutput(),
                outputVariance, plannedCost, execution.actualCost(), costVariance,
                plan.requiredLaborHours(), execution.actualLaborHours(), laborVariance,
                performance, issues, provenance);
    }

    public EconomicSliceEvidence economicEvidence(FarmOperatingPlan plan, ExecutionOutcome execution,
            double plannedRevenue, double actualRevenue, String allocationBasis, String provenance) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(execution);
        double plannedCost = plan.plannedLaborCost() + plan.plannedResourceCost();
        double actualCost = execution.actualCost();
        double plannedContribution = plannedRevenue - plannedCost;
        double actualContribution = actualRevenue - actualCost;
        return new EconomicSliceEvidence(plannedRevenue, actualRevenue, plannedCost, actualCost,
                plannedContribution, actualContribution, actualContribution - plannedContribution,
                Objects.requireNonNull(allocationBasis),
                "Workforce emits operational economic evidence; Economy remains authoritative for accounting truth",
                Objects.requireNonNull(provenance));
    }

    public LearningImprovement learn(CycleReport report, String rootCause, String lesson,
            boolean validationEvidence, String provenance) {
        Objects.requireNonNull(report);
        Objects.requireNonNull(rootCause);
        Objects.requireNonNull(lesson);
        Objects.requireNonNull(provenance);
        boolean validated = validationEvidence && report.performance().equals("TARGET_MET");
        double improved = validated ? Math.max(report.plannedOutput(), report.actualOutput()) : report.plannedOutput();
        return new LearningImprovement("improvement-" + report.reportId(), report.plannedOutput(),
                improved, improved - report.plannedOutput(), rootCause, lesson, validated, provenance);
    }

    public FarmOperatingPlan applyValidatedImprovement(FarmOperatingPlan current, LearningImprovement improvement) {
        Objects.requireNonNull(current);
        Objects.requireNonNull(improvement);
        if (!improvement.validated()) return current;
        return new FarmOperatingPlan(current.planId() + "-next", current.farmId(),
                current.requiredLaborHours(), current.availableLaborHours(), current.capacityDeficitHours(),
                current.workerCount(), current.shiftHoursPerWorker(), current.plannedLaborCost(),
                current.plannedResourceCost(), improvement.improvedOutput(), current.schedule(), current.risks(),
                current.provenance() + ";validated-improvement=" + improvement.candidateId());
    }

    public VerticalSliceResult run(VerticalSliceRequest request, String farmId, int requiredLaborHours,
            int workerCount, int shiftHoursPerWorker, double hourlyLaborCost, double resourceCost,
            double expectedOutput, double actualOutput, double actualCost, double plannedRevenue,
            double actualRevenue, String schedule, String risks) {
        Objects.requireNonNull(request);
        Assignment assignment = new Assignment("assignment-" + request.requestId(),
                request.headOfWorkforceId(), request.farmHeadId(),
                "Own the farm operating plan for the requested cycle", Instant.now(),
                "request=" + request.requestId());
        FarmOperatingPlan plan = plan("plan-" + request.requestId(), farmId, requiredLaborHours,
                workerCount, shiftHoursPerWorker, hourlyLaborCost, resourceCost, expectedOutput,
                schedule, risks, "assignment=" + assignment.assignmentId());
        Approval approval = approve(plan, "AUTHORITY:" + request.headOfWorkforceId(),
                "request=" + request.requestId());
        ExecutionOutcome execution = execute(plan, approval, plan.requiredLaborHours(), actualCost,
                actualOutput, "plan=" + plan.planId() + ";authority=" + approval.authorityReference());
        CycleReport report = report(plan, execution, "execution=" + execution.executionId());
        EconomicSliceEvidence economic = economicEvidence(plan, execution, plannedRevenue, actualRevenue,
                "labor-hours + resource allocation basis", "report=" + report.reportId());
        LearningImprovement learning = learn(report,
                report.outputVariance() < 0 ? "forecast-error" : "validated-operating-pattern",
                report.outputVariance() < 0 ? "recalibrate the next planning forecast" : "retain the validated operating pattern",
                execution.success(), "report=" + report.reportId());
        FarmOperatingPlan nextPlan = applyValidatedImprovement(plan, learning);
        return new VerticalSliceResult(request, assignment, plan, approval, execution, report, economic,
                learning, nextPlan, "PENDING_HUMAN_REVIEW");
    }

    public VerticalSliceResult review(VerticalSliceResult result, String decision) {
        Objects.requireNonNull(result);
        Objects.requireNonNull(decision);
        if (decision.isBlank()) throw new IllegalArgumentException("review decision is required");
        return new VerticalSliceResult(result.request(), result.assignment(), result.plan(), result.approval(),
                result.execution(), result.report(), result.economicEvidence(), result.learning(),
                result.nextPlan(), decision);
    }
}
