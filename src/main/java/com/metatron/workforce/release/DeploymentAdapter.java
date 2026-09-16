package com.metatron.workforce.release;

/**
 * Boundary for the host deployment/verification executor. The existing broker/Commander host tools
 * (workforce_deploy_local_sha, workforce_verify_production) are the only current implementation of this
 * behavior, and their own authorization boundary -- which client identities may invoke them -- is not
 * resolved (see the accompanying audit: a Direct-MCP client can currently call them directly). This
 * interface therefore does not call those host tools and does not reimplement a deployment engine in
 * Java.
 *
 * {@link #UNAVAILABLE} is the default and refuses to produce any deployment or verification evidence at
 * all, so no caller can satisfy PRODUCTION_REQUIRED completion merely by asserting a SHA or claiming a
 * host tool returned PASS -- there is currently no path in this codebase from "I have a string that
 * looks like a SHA" to a durable, accepted deployment/verification record. A future adapter, wired only
 * after the broker itself enforces a privileged Management-only caller identity, can implement this
 * interface and be composed into {@link ReleaseControlService} without changing that service, the
 * evidence model, or the completion gate.
 */
public interface DeploymentAdapter {
    DeploymentOutcome deploy(String mergeSha);
    VerificationOutcome verify(String expectedSha);

    record DeploymentOutcome(boolean deployed, String deployedSha, String imageDigest, String blockedReason) {
        public static DeploymentOutcome blocked(String reason) { return new DeploymentOutcome(false, null, null, reason); }
    }

    /** observedSha == null signals "no verification was actually performed" -- never treated as evidence. */
    record VerificationOutcome(String observedSha, ReleaseEvidence.VerificationStatus status) {}

    DeploymentAdapter UNAVAILABLE = new DeploymentAdapter() {
        @Override public DeploymentOutcome deploy(String mergeSha) {
            return DeploymentOutcome.blocked("deployment_adapter_unavailable");
        }
        @Override public VerificationOutcome verify(String expectedSha) {
            return new VerificationOutcome(null, ReleaseEvidence.VerificationStatus.NONE);
        }
    };
}
