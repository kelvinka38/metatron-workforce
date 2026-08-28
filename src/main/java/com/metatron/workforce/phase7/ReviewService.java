package com.metatron.workforce.phase7;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ReviewService {
    private final Map<String, InstitutionalReview> reviews = new ConcurrentHashMap<>();
    private final ReviewStateStore store;

    public ReviewService() { this(new ReviewStateStore() {
        private List<InstitutionalReview> state = List.of();
        public List<InstitutionalReview> load(){ return state; }
        public void save(List<InstitutionalReview> reviews){ state = List.copyOf(reviews); }
    }); }

    public ReviewService(ReviewStateStore store) {
        this.store = Objects.requireNonNull(store);
        store.load().forEach(r -> reviews.put(r.reviewId(), r));
    }

    public synchronized InstitutionalReview record(String reviewId, String subjectRef, String reviewerWorkerId,
            String reviewerRoleRef, String authorityRef, InstitutionalReview.Decision decision,
            String rationale, List<String> evidenceRefs, Instant at) {
        InstitutionalReview review = new InstitutionalReview(reviewId, subjectRef, reviewerWorkerId, reviewerRoleRef,
                authorityRef, decision, rationale, evidenceRefs, at);
        if (reviews.putIfAbsent(reviewId, review) != null) throw new IllegalStateException("review already exists");
        persist(); return review;
    }

    public InstitutionalReview get(String id) {
        return Optional.ofNullable(reviews.get(id)).orElseThrow(() -> new NoSuchElementException("review not found"));
    }

    public List<InstitutionalReview> forSubject(String subjectRef) {
        return reviews.values().stream().filter(r -> r.subjectRef().equals(subjectRef)).toList();
    }
    private void persist(){ store.save(List.copyOf(reviews.values())); }
}
