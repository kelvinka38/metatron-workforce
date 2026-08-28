package com.metatron.workforce.phase7;

import java.time.Instant;
import java.util.List;

public record InstitutionalReview(
        String reviewId,
        String subjectRef,
        String reviewerWorkerId,
        String reviewerRoleRef,
        String authorityRef,
        Decision decision,
        String rationale,
        List<String> evidenceRefs,
        Instant reviewedAt) {
    public enum Decision { APPROVED, REJECTED, REVISION_REQUIRED, ESCALATED }
    public InstitutionalReview {
        require(reviewId,"reviewId"); require(subjectRef,"subjectRef"); require(reviewerWorkerId,"reviewerWorkerId");
        require(reviewerRoleRef,"reviewerRoleRef"); require(authorityRef,"authorityRef"); require(rationale,"rationale");
        if (decision == null || reviewedAt == null) throw new IllegalArgumentException("decision/reviewedAt required");
        evidenceRefs = List.copyOf(evidenceRefs == null ? List.of() : evidenceRefs);
        if (evidenceRefs.isEmpty()) throw new IllegalArgumentException("review requires evidence");
    }
    private static void require(String v,String n){ if(v==null||v.isBlank()) throw new IllegalArgumentException(n+" required"); }
}
