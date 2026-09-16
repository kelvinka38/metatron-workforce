package com.metatron.workforce.release;

import java.util.Objects;

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
     * Requires recorded PR evidence for this exact assignment first, verifies the authorization
     * identifies THAT SAME recorded PR/head, then re-fetches live PR state and compares it to
     * authorization.expectedHeadSha() before ever invoking the MergeExecutor. Prevents: record PR A's
     * evidence, submit a MergeAuthorization for PR B, merge B, have B's merge SHA recorded against A.
     */
    public MergeExecutor.MergeOutcome requestMerge(MergeAuthorization authorization) {
        Objects.requireNonNull(authorization);
        ReleaseEvidence recorded = evidence.get(authorization.assignmentId());
        if (recorded == null || !recorded.satisfiesPrRequired())
            return MergeExecutor.MergeOutcome.blocked("no_recorded_pr_evidence_for_assignment");
        if (!recorded.repository().equals(authorization.repository())
                || !recorded.prNumber().equals(authorization.prNumber())
                || !recorded.prHeadSha().equals(authorization.expectedHeadSha())) {
            return MergeExecutor.MergeOutcome.blocked("merge_authorization_does_not_match_recorded_pr_evidence");
        }
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
