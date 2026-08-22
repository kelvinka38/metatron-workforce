package com.metatron.workforce.phase6;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ProposalServiceTest {
    private static final Instant CREATED = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant DECIDED = Instant.parse("2026-08-20T10:00:00Z");

    private Proposal draft() {
        return new Proposal(
                "proposal-001", "worker-001", "org-A", "EXECUTE_WORK", "farm-A",
                Proposal.State.DRAFT, CREATED, null, null, "evidence-001");
    }

    @Test
    void proposalCanBeSubmittedAndApproved() {
        ProposalService service = new ProposalService();
        Proposal submitted = service.submit(draft());
        Proposal underReview = service.startReview(submitted);
        Proposal approved = service.approve(underReview, DECIDED, "approval authority satisfied");

        assertEquals(Proposal.State.APPROVED, approved.state());
        assertTrue(approved.decided());
        assertEquals(DECIDED, approved.decidedAt());
        assertEquals("approval authority satisfied", approved.decisionReason());
    }

    @Test
    void rejectionReturnDeferAndPartialApprovalRemainDistinct() {
        ProposalService service = new ProposalService();

        Proposal rejected = service.reject(service.startReview(service.submit(draft())), DECIDED, "policy denied");
        Proposal returned = service.returnForRevision(service.startReview(service.submit(draft())), DECIDED, "missing scope");
        Proposal deferred = service.defer(service.startReview(service.submit(draft())), DECIDED, "budget pending");
        Proposal partial = service.partiallyApprove(service.startReview(service.submit(draft())), DECIDED, "partial scope approved");

        assertEquals(Proposal.State.REJECTED, rejected.state());
        assertEquals(Proposal.State.RETURNED, returned.state());
        assertEquals(Proposal.State.DEFERRED, deferred.state());
        assertEquals(Proposal.State.PARTIALLY_APPROVED, partial.state());
    }

    @Test
    void decisionCannotBeMadeBeforeReview() {
        ProposalService service = new ProposalService();
        assertThrows(IllegalStateException.class,
                () -> service.approve(service.submit(draft()), DECIDED, "not reviewed"));
    }
}
