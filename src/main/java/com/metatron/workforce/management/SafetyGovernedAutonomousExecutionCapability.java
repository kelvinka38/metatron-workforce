package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Final operational gate in front of the governed capability effect.
 * Duplicate delivery of the same durable dispatch is charged once by AutonomySafetyService.
 */
public final class SafetyGovernedAutonomousExecutionCapability implements AutonomousExecutionCapability {
    private final AutonomousExecutionCapability delegate;
    private final AutonomySafetyService safety;
    private final Clock clock;

    public SafetyGovernedAutonomousExecutionCapability(AutonomousExecutionCapability delegate,
                                                       AutonomySafetyService safety, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.safety = Objects.requireNonNull(safety, "safety");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public String capabilityRef() { return delegate.capabilityRef(); }
    @Override public String capabilityDescription() { return delegate.capabilityDescription(); }
    @Override public String authorityReference() { return delegate.authorityReference(); }
    @Override public String authorizationReference() { return delegate.authorizationReference(); }
    @Override public double minimumCapabilityLevel() { return delegate.minimumCapabilityLevel(); }
    @Override public double requiredCapacity() { return delegate.requiredCapacity(); }
    @Override public boolean supportsWorker(String workerId) { return delegate.supportsWorker(workerId); }
    @Override public boolean supportsWorker(String workerId, ExecutionWorkSpec workSpec) {
        return delegate.supportsWorker(workerId, workSpec);
    }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        String dispatchReference = request.dispatchBound()
                ? request.dispatchReference()
                : request.idempotencyKey() + ":compat-attempt:1";
        int attempt = request.dispatchBound() ? request.dispatchAttempt() : 1;
        AutonomySafetyService.DispatchBudgetReceipt receipt = safety.reserveDispatch(
                request.objectiveId(), dispatchReference, attempt,
                requireAuthority(delegate.authorityReference()), request.workSpec().consequence(),
                Math.max(0.000001d, delegate.requiredCapacity()), clock.instant());
        CapabilityResult result = delegate.execute(request);
        List<String> evidence = new ArrayList<>(result.evidenceReferences());
        evidence.add("autonomy-safety:dispatch=" + receipt.dispatchReference()
                + ":cost_units=" + receipt.chargedCostUnits()
                + ":consumed_cost_units=" + receipt.consumedCostUnits()
                + ":max_cost_units=" + receipt.maxCostUnits()
                + ":attempts=" + receipt.consumedDispatchAttempts() + "/" + receipt.maxDispatchAttempts()
                + ":safety_version=" + receipt.safetyVersion()
                + ":replay=" + receipt.replay());
        return new CapabilityResult(result.success(), result.workerId(), result.assignmentReference(),
                result.workReference(), evidence, result.summary());
    }

    private static String requireAuthority(String value) {
        if (value == null || value.isBlank()) throw new SecurityException("authority-reference-missing");
        return value.trim();
    }
}
