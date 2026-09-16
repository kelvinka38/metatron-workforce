package com.metatron.workforce.release;

public interface MergeExecutor {
    MergeOutcome merge(MergeAuthorization authorization);

    record MergeOutcome(boolean merged, String mergeSha, String blockedReason) {
        public static MergeOutcome merged(String mergeSha) { return new MergeOutcome(true, mergeSha, null); }
        public static MergeOutcome blocked(String reason) { return new MergeOutcome(false, null, reason); }
    }

    MergeExecutor UNAVAILABLE = authorization -> MergeOutcome.blocked("privileged_merge_executor_unavailable");
}
