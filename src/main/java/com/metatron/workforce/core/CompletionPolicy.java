package com.metatron.workforce.core;

/**
 * Immutable completion policy established when an Assignment is created (see
 * {@link WorkforceCoreService#assign}). Determines what evidence {@link WorkforceCoreService#transitionAssignment}
 * requires before it will allow a transition to {@code AssignmentStatus.COMPLETED}.
 *
 * There is no setter anywhere in this codebase: the policy is chosen once, at creation time, by
 * whichever caller establishes the Assignment (management/planning code), and nothing afterward --
 * including the Worker performing the work -- can downgrade it, because Assignment is an immutable
 * record and transitionAssignment() never accepts a policy argument.
 */
public enum CompletionPolicy {
    /**
     * Default, backward-compatible policy: execution success alone (the existing pre-Release-Control-Plane
     * behavior) is sufficient for COMPLETED. Every Assignment created through an existing call site that
     * has not been updated to specify a policy explicitly gets this value.
     */
    EXECUTION_REQUIRED,

    /** Requires recorded PR evidence (repository + PR number + PR head SHA) before COMPLETED. */
    PR_REQUIRED,

    /**
     * Requires the full release evidence chain -- PR evidence, a recorded merge SHA, a deployment of
     * exactly that merge SHA, and a passed production verification observing exactly that SHA -- before
     * COMPLETED.
     */
    PRODUCTION_REQUIRED
}
