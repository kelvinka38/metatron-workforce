package com.metatron.workforce.release;

/**
 * Privileged executor that actually performs a GitHub merge using server-side credentials never exposed
 * to Workers. No implementation exists in this codebase yet -- see {@link PullRequestStateReader} for why.
 * {@link #UNAVAILABLE} always returns BLOCKED; the interface exists so a real privileged implementation
 * can be wired into {@link ReleaseControlService} later without changing that service, the evidence model,
 * or the completion gate.
 */
public interface MergeExecutor {
    MergeOutcome merge(MergeAuthorization authorization);

    record MergeOutcome(boolean merged, String mergeSha, String blockedReason) {
        public static MergeOutcome merged(String mergeSha) { return new MergeOutcome(true, mergeSha, null); }
        public static MergeOutcome blocked(String reason) { return new MergeOutcome(false, null, reason); }
    }

    MergeExecutor UNAVAILABLE = authorization -> MergeOutcome.blocked("privileged_merge_executor_unavailable");
}
