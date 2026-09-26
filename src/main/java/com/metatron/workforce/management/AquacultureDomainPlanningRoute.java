package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.PositionWorkRoute;
import org.springframework.stereotype.Component;

/**
 * Work planned for an Objective addressed to the Head of Aquaculture's Position: one governed MUTATING
 * aquaculture.domain.planning step on kelvinka38/bios whose output is an unmerged pull request (PR #543 behavior).
 */
@Component
public final class AquacultureDomainPlanningRoute implements PositionWorkRoute {
    @Override
    public String capability() {
        return AquacultureDomainPlanningCapability.CAPABILITY;
    }

    @Override
    public ExecutionWorkSpec work(String stepId, String objective) {
        return AquacultureDomainPlanningCapability.actionPlanWork(stepId, objective);
    }
}
