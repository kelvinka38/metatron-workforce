package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Root-cause fix (2026-09-23, Founder-directed): AGENTS.md's "Chat -> no automatic durable-Work handoff
 * by default" boundary is real and stays real -- {@code ChannelInteractionIngressService
 * .semanticChatExecutionHandoffEnabled()} is still hardcoded {@code false}. What changed is that an
 * ordinary chat message the frontier pass genuinely understands as an execution request no longer gets
 * a flat "not admitted, go learn the canonical grammar" rejection. It gets asked a specific yes/no
 * question instead, and a bare "yes" from the Human -- an explicit act, not automatic chat routing -- is
 * what admits it, reusing the exact synthetic-canonical-control-string handoff path
 * {@code WorkplaceMeetingService.handoffFollowUp} already uses for meeting-derived Work.
 */
final class ChatExecutionConfirmationTest {

    @Test
    void affirmativeRepliesAreRecognizedInEnglishAndVietnamese() {
        assertTrue(MetatronIntelligenceResponder.isAffirmativeExecutionConfirmation("yes"));
        assertTrue(MetatronIntelligenceResponder.isAffirmativeExecutionConfirmation("Yes!"));
        assertTrue(MetatronIntelligenceResponder.isAffirmativeExecutionConfirmation("  confirm please"));
        assertTrue(MetatronIntelligenceResponder.isAffirmativeExecutionConfirmation("ok go ahead"));
        assertTrue(MetatronIntelligenceResponder.isAffirmativeExecutionConfirmation("đồng ý"));
        assertTrue(MetatronIntelligenceResponder.isAffirmativeExecutionConfirmation("xác nhận đi"));
    }

    @Test
    void unrelatedOrNegativeRepliesAreNotRecognizedAsConfirmation() {
        assertFalse(MetatronIntelligenceResponder.isAffirmativeExecutionConfirmation("no"));
        assertFalse(MetatronIntelligenceResponder.isAffirmativeExecutionConfirmation("actually build something else instead"));
        assertFalse(MetatronIntelligenceResponder.isAffirmativeExecutionConfirmation("what's the weather"));
        assertFalse(MetatronIntelligenceResponder.isAffirmativeExecutionConfirmation(""));
    }

    @Test
    void confirmationRequiredResponseNamesTheCaseAndTheUnderstoodObjective() {
        Instant now = Instant.parse("2026-09-23T00:00:00Z");
        IntelligenceCase pending = new IntelligenceCase(
                "case-pending", "conversation:human:founder", "human:founder",
                "build and deliver a recipe-sharing app called Kitchen Companion",
                IntelligenceDepth.ANALYZE, IntelligenceCaseStatus.OPEN,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "", "",
                List.of(), now, now);

        String response = MetatronIntelligenceResponder.executionConfirmationRequiredResponse(
                pending, pending.objective());

        assertTrue(response.contains("case-pending"));
        assertTrue(response.contains("Kitchen Companion"));
        assertTrue(response.toLowerCase(java.util.Locale.ROOT).contains("yes"),
                "the prompt must tell the Human the exact word that admits the work");
    }

    @Test
    void aBareYesToAPendingConfirmationAdmitsDurableWorkThroughTheRealHandoffPath() {
        AtomicInteger handoffs = new AtomicInteger();
        AtomicReference<NormalizedRequest> submitted = new AtomicReference<>();
        ExecutionObjectiveHandoff handoff = (humanId, organizationContextId, caseId, conversationId,
                                             externalMessageReference, channel, request) -> {
            handoffs.incrementAndGet();
            submitted.set(request);
            return new ExecutionObjectiveHandoff.HandoffReceipt(
                    true, "objective:kitchen-companion", "WORKER-GENERAL-ENGINEERING", "queue-1",
                    "ACCEPTED", "ADMITTED", "chat-confirmed");
        };
        InMemoryIntelligenceCaseStore caseStore = new InMemoryIntelligenceCaseStore();
        MetatronIntelligenceResponder responder = new MetatronIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper(),
                "", "", caseStore, handoff, false);

        Instant now = Instant.parse("2026-09-23T00:00:00Z");
        IntelligenceCase pending = new IntelligenceCase(
                "case-pending", "conversation:human:founder", "human:founder",
                "build and deliver a recipe-sharing app called Kitchen Companion",
                IntelligenceDepth.ANALYZE, IntelligenceCaseStatus.AWAITING_EXECUTION_CONFIRMATION,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "", "",
                List.of(), now, now);
        caseStore.save(pending);

        String response = responder.respond(
                "founder", "yes", "telegram:update:confirm-1", "telegram",
                "conversation:human:founder", "organization:metatron", "");

        assertEquals(1, handoffs.get(), "a bare 'yes' to the exact pending question must admit the work exactly once");
        assertTrue(response.contains("METATRON WORK ACCEPTED"));
        assertTrue(response.contains("objective:kitchen-companion"));
        assertTrue(submitted.get() != null && submitted.get().objective().contains("Kitchen Companion"),
                "the confirmed handoff must carry the objective the Human already saw and confirmed");
        assertEquals(IntelligenceCaseStatus.RESOLVED, caseStore.findByCaseId("case-pending").orElseThrow().status());
    }

    @Test
    void aNonAffirmativeReplyAbandonsThePendingConfirmationInsteadOfSilentlyDroppingTheMessage() {
        AtomicInteger handoffs = new AtomicInteger();
        ExecutionObjectiveHandoff handoff = (humanId, organizationContextId, caseId, conversationId,
                                             externalMessageReference, channel, request) -> {
            handoffs.incrementAndGet();
            return new ExecutionObjectiveHandoff.HandoffReceipt(
                    true, "objective:wrong", "worker", "queue", "ACCEPTED", "ADMITTED", "wrong");
        };
        InMemoryIntelligenceCaseStore caseStore = new InMemoryIntelligenceCaseStore();
        MetatronIntelligenceResponder responder = new MetatronIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper(),
                "", "", caseStore, handoff, false);

        Instant now = Instant.parse("2026-09-23T00:00:00Z");
        IntelligenceCase pending = new IntelligenceCase(
                "case-pending", "conversation:human:founder", "human:founder",
                "build and deliver a recipe-sharing app called Kitchen Companion",
                IntelligenceDepth.ANALYZE, IntelligenceCaseStatus.AWAITING_EXECUTION_CONFIRMATION,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "", "",
                List.of(), now, now);
        caseStore.save(pending);

        // Deterministic explicit control so this fresh message can complete without a live semantic
        // provider (the test's empty API keys mean any non-deterministic text throws
        // semantic_provider_required) -- the point under test is that the OLD pending confirmation is
        // abandoned rather than silently consuming this unrelated new message.
        String response = responder.respond(
                "founder",
                "Take ownership of one governed Objective: fix the login bug. Assign the implementation to WORKER-GENERAL-ENGINEERING.",
                "telegram:update:unrelated-1", "telegram",
                "conversation:human:founder", "organization:metatron", "");

        assertEquals(1, handoffs.get(), "the fresh, unrelated message must still be admitted on its own merits");
        assertEquals(IntelligenceCaseStatus.RESOLVED, caseStore.findByCaseId("case-pending").orElseThrow().status(),
                "the abandoned pending confirmation must not be left dangling");
    }
}
