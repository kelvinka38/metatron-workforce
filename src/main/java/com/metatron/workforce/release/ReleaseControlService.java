package com.metatron.workforce.release;

import java.util.Objects;

/**
 * Management-only governed release control surface. This class is never registered as a Worker/Direct-MCP
 * tool or action: {@code DirectCodingIngressService.ACTIONS} does not and must not include any method
 * here. It only records evidence and drives a merge/deploy/verify state machine; it never accepts a
 * caller-supplied claim ("deployedSha=X", "verification passed") without the producing step of its own
 * having generated that value first -- enforced by {@link ReleaseEvidenceStore}'s monotonic checks.
 */
public class ReleaseControlService {
    private final ReleaseEvidenceStore evidence;
    private final MergeExecutor mergeExecutor;
    private final PullRequestStateReader prReader;
    private final DeploymentAdapter deploymentAdapter;

    public ReleaseControlService(ReleaseEvidenceStore evidence) {
        this(evidence, MergeExecutor.UNAVAILABLE, PullRequestStateReader.UNAVAILABLE, DeploymentAdapter.UNAVAILABLE);
    }

    public ReleaseControlService(ReleaseEvidenceStore evidence, MergeExecutor mergeExecutor,
            PullRequestStateReader prReader, DeploymentAdapter deploymentAdapter) {
        this.evidence = Objects.requireNonNull(evidence);
        this.mergeExecutor = Objects.requireNonNull(mergeExecutor);
        this.prReader = Objects.requireNonNull(prReader);
        this.deploymentAdapter = Objects.requireNonNull(deploymentAdapter);
    }

    public ReleaseEvidence recordPrPublished(String assignmentId, String objectiveRef, String repository,
            int prNumber, String prHeadSha, String baseBranch, String baseSha, String producer) {
        return evidence.recordPrPublished(assignmentId, objectiveRef, repository, prNumber, prHeadSha, baseBranch, baseSha, producer);
    }

    /**
     * Re-fetches live PR state and compares it to {@code authorization.expectedHeadSha()} before ever
     * invoking the {@link MergeExecutor}. Blocks on a stale head, failed required checks, or unsatisfied
     * branch protection -- never bypasses any of them.
     */
    public MergeExecutor.MergeOutcome requestMerge(MergeAuthorization authorization) {
        Objects.requireNonNull(authorization);
        PullRequestStateReader.PullRequestState state;
        try {
            state = prReader.read(authorization.repository(), authorization.prNumber());
        } catch (RuntimeException unavailable) {
            return MergeExecutor.MergeOutcome.blocked("pull_request_state_unavailable:" + unavailable.getMessage());
        }
        if (state == null || !authorization.expectedHeadSha().equals(state.headSha()))
            return MergeExecutor.MergeOutcome.blocked("stale_pr_head");
        if (!state.requiredChecksPassed())
            return MergeExecutor.MergeOutcome.blocked("required_checks_not_satisfied");
        if (!state.approvalsSatisfied())
            return MergeExecutor.MergeOutcome.blocked("branch_protection_not_satisfied");
        MergeExecutor.MergeOutcome outcome = mergeExecutor.merge(authorization);
        if (outcome.merged()) evidence.recordMerge(authorization.assignmentId(), outcome.mergeSha(), "release-control:merge");
        return outcome;
    }

    /** deployedSha in the outcome must equal the previously recorded merge SHA or the record is rejected. */
    public DeploymentAdapter.DeploymentOutcome requestDeployment(String assignmentId, String mergeSha) {
        ReleaseEvidence current = evidence.get(assignmentId);
        if (current == null || current.mergeSha() == null)
            return DeploymentAdapter.DeploymentOutcome.blocked("no_recorded_merge_evidence");
        if (!current.mergeSha().equals(mergeSha))
            return DeploymentAdapter.DeploymentOutcome.blocked("sha_not_derived_from_recorded_merge");
        DeploymentAdapter.DeploymentOutcome outcome = deploymentAdapter.deploy(mergeSha);
        if (outcome.deployed())
            evidence.recordDeployment(assignmentId, outcome.deployedSha(), outcome.imageDigest(), "release-control:deploy");
        return outcome;
    }

    public ReleaseEvidence.VerificationStatus requestVerification(String assignmentId, String expectedSha) {
        DeploymentAdapter.VerificationOutcome outcome = deploymentAdapter.verify(expectedSha);
        if (outcome.observedSha() == null) return ReleaseEvidence.VerificationStatus.NONE;
        ReleaseEvidence updated = evidence.recordVerification(
                assignmentId, expectedSha, outcome.observedSha(), outcome.status(), "release-control:verify");
        return updated.verificationStatus();
    }

    public ReleaseEvidence evidenceFor(String assignmentId) { return evidence.get(assignmentId); }
}
