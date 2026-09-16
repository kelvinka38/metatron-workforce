package com.metatron.workforce.release;

/**
 * Reads live PR state (current head SHA, required-check status, approval/branch-protection status)
 * needed to authorize a merge. No implementation exists in this codebase yet: the governed server-side
 * GitHub credential path used by the existing {@code repository_pr_publish} action lives outside this
 * Java application (see the accompanying audit), and this PR does not introduce a new credential path
 * to avoid creating a second, less-audited way to reach GitHub. {@link #UNAVAILABLE} causes
 * {@link ReleaseControlService#requestMerge} to fail closed with a truthful BLOCKED result rather than
 * skip the check or fabricate a passing state.
 */
public interface PullRequestStateReader {
    PullRequestState read(String repository, int prNumber);

    record PullRequestState(String headSha, boolean requiredChecksPassed, boolean approvalsSatisfied) {}

    PullRequestStateReader UNAVAILABLE = (repository, prNumber) -> {
        throw new IllegalStateException("pull_request_state_reader_unavailable");
    };
}
