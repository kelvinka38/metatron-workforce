package com.metatron.workforce.release;

/**
 * Immutable request to merge one specific, already-approved PR at one specific head SHA.
 * {@link ReleaseControlService#requestMerge} re-fetches the PR's live state via
 * {@link PullRequestStateReader} and compares it to {@code expectedHeadSha} before ever invoking a
 * {@link MergeExecutor}: any mismatch (the PR changed after evidence/approval was collected) blocks the
 * merge unconditionally, regardless of what the caller believes the state to be.
 */
public record MergeAuthorization(String assignmentId, String repository, int prNumber, String expectedHeadSha) {}
