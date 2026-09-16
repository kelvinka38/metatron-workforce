package com.metatron.workforce.management;

import com.metatron.workforce.core.CompletionPolicy;

/**
 * Authoritative, Objective-level completion gate consulted by ManagementAutonomyService.completeAutonomousObjective()
 * -- the lowest common Objective-completion transition boundary -- so no caller can invoke that method
 * and bypass the Objective's declared release requirement, regardless of whether it is reached through
 * the normal AutonomousManagementRunner path or any other caller.
 */
public interface ObjectiveCompletionGate {
    boolean satisfiesCompletion(String objectiveId, CompletionPolicy policy, AutonomousObjectiveWork work);

    /** Safe default: preserves today's behavior exactly (no release-policy check) until a real gate is wired in. */
    ObjectiveCompletionGate ALWAYS_SATISFIED = (objectiveId, policy, work) -> true;
}
