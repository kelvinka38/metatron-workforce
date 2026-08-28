package com.metatron.workforce.phase7;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ReviewService {
    private final Map<String, InstitutionalReview> reviews = new ConcurrentHashMap<>();

    public synchronized InstitutionalReview record(String reviewId, String subjectRef, String reviewerWorkerId,
            String reviewerRoleRef, String authorityRef, InstitutionalReview.Decision decision,
            String rationale, List<String> evidenceRefs, Instant at) {
        InstitutionalReview review = new InstitutionalReview(reviewId, subjectRef, reviewerWorkerId, reviewerRoleRef,
                authorityRef, decision, rationale, evidenceRefs, at);
        if (reviews.putIfAbsent(reviewId, review) != null) throw new IllegalStateException("review already exists");
        return review;
    }

    public InstitutionalReview get(String id) {
        return Optional.ofNullable(reviews.get(id)).orElseThrow(() -> new NoSuchElementException("review not found"));
    }

    public List<InstitutionalReview> forSubject(String subjectRef) {
        return reviews.values().stream().filter(r -> r.subjectRef().equals(subjectRef)).toList();
    }
}
