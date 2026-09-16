package com.metatron.workforce.release;

public interface DeploymentAdapter {
    DeploymentOutcome deploy(String mergeSha);
    VerificationOutcome verify(String expectedSha);

    record DeploymentOutcome(boolean deployed, String deployedSha, String imageDigest, String blockedReason) {
        public static DeploymentOutcome blocked(String reason) { return new DeploymentOutcome(false, null, null, reason); }
    }

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
