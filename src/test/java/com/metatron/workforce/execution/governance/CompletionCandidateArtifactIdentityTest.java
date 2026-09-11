package com.metatron.workforce.execution.governance;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionCandidateArtifactIdentityTest {
    private static final String SOURCE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TESTED = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void sourceShaOnlyIsProposalProvenanceNotReleaseIdentity() {
        CompletionCandidate candidate = candidate(SOURCE, "", "", "", "");
        assertFalse(candidate.carriesArtifactIdentity(),
                "proposal source provenance must not activate the five-way release artifact identity contract");
    }

    @Test
    void anyDownstreamArtifactShaActivatesFailClosedReleaseIdentityContract() {
        CompletionCandidate candidate = candidate(SOURCE, TESTED, "", "", "");
        assertTrue(candidate.carriesArtifactIdentity(),
                "once a downstream artifact identity is asserted the exact-SHA release chain must be enforced");
    }

    private static CompletionCandidate candidate(String source, String tested, String approved,
                                                   String deployed, String observed) {
        return new CompletionCandidate(
                "objective-1", "step-1", "plan-1", 1, "authority-1",
                List.of("github-general-proposal:true"), List.of("step-1"), List.of(),
                Map.of("criterion", "observation-report:criterion"),
                Map.of("evidence", "observation-report:evidence"), true,
                source, tested, approved, deployed, observed, Instant.parse("2026-09-11T00:00:00Z"));
    }
}
