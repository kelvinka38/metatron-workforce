package com.metatron.workforce.management;

/**
 * Typed classification of a step-execution failure, used by {@link AutonomousRecoveryPolicy} to
 * decide whether the bounded local/read-only retry may fire at all. Only {@link #TRANSIENT} is ever
 * automatically retried; the other three always escalate for Human review on the first attempt,
 * exactly like an explicit authorization/data/safety-gate denial already did before this type existed.
 */
enum FailureClassification {
    /**
     * A failure whose cause is external and may genuinely differ on the next attempt (network
     * blip, provider timeout, transient infrastructure error). Safe to retry within the existing
     * bounded attempt budget.
     */
    TRANSIENT,
    /**
     * A failure that will recur identically on an unmodified retry because it is a property of the
     * request/workspace/action contract itself, not of external conditions: an oversized cognition
     * request against its own budget, an action's own declared input contract violated, an
     * unsupported/missing project system, or the in-runtime repeated-failure circuit breaker already
     * having determined the same action fails identically twice with no intervening state change.
     * Retrying without a repair changes nothing, so this must never consume the bounded-retry budget.
     */
    DETERMINISTIC_CONTRACT,
    /**
     * An explicit authority/authorization/ExecutionPermit/safety-gate denial. Governance said no;
     * retrying the identical request cannot change that answer, and only a Human/operator decision
     * (or a governed re-authorization) can.
     */
    AUTHORIZATION_GOVERNANCE,
    /**
     * An operational condition only a Human can resolve that is neither a contract defect nor a
     * governance denial: a staffing gap or unavailable capacity.
     */
    HUMAN_REQUIRED
}
