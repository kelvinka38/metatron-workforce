package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import org.springframework.stereotype.Component;

/** Founder-approved bounded formation policy for the PR-only Golden Slice 2 worker. */
@Component
public final class RepositoryPullRequestStaffingPolicy implements AutonomousStaffingPolicy {
    @Override public String capabilityRef() { return RepositoryPullRequestAutonomousCapability.CAPABILITY; }

    @Override
    public FormationSpec formationSpec() {
        return new FormationSpec(
                true,
                "participant:repository-pr-proposer",
                WorkforceCoreService.ParticipantType.AI,
                "provenance:workforce-governed-repository-pr-proposer:v1",
                RepositoryPullRequestAutonomousCapability.WORKER_ID,
                "organization:metatron",
                "participation:repository-pr-proposer:metatron",
                "position:repository-pr-proposer",
                "role:bounded-repository-maintainer",
                1.0,
                "evidence:repository-pr-capability-acceptance:v1",
                "qualification:repository-pr-gap-matrix-only:v1",
                "evidence:repository-pr-qualification:v1",
                RepositoryPullRequestAutonomousCapability.AUTHORITY_REFERENCE,
                1.0,
                "runtime-profile:repository-pr-proposer:v1",
                "cost-limit:repository-pr-bounded:v1",
                "lifecycle:repository-pr-proposer:v1");
    }
}
