package com.metatron.workforce.release;

public interface PullRequestStateReader {
    PullRequestState read(String repository, int prNumber);

    record PullRequestState(String headSha, boolean requiredChecksPassed, boolean approvalsSatisfied) {}

    PullRequestStateReader UNAVAILABLE = (repository, prNumber) -> {
        throw new IllegalStateException("pull_request_state_reader_unavailable");
    };
}
