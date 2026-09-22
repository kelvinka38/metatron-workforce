package com.metatron.workforce.interaction.channel;

import com.metatron.workforce.interaction.intelligence.AnalyticalProtocolType;
import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.DeterministicCapability;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepth;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.management.AutonomousManagementRunner;
import com.metatron.workforce.management.AutonomySafetyService;
import com.metatron.workforce.management.ManagementAutonomyService;
import com.metatron.workforce.management.ManagementObjective;
import com.metatron.workforce.workplace.InMemoryWorkplaceContinuityStateStore;
import com.metatron.workforce.workplace.WorkplaceContinuityService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Production gap (2026-09-22): once an Objective goes BLOCKED/ESCALATED, the only way to make it
 * move again was to submit an entirely new Objective from scratch -- the governed control-plane
 * {@code /resume} capability existed (AutonomyControlController) but was never exposed to Telegram,
 * the channel the Human actually operates through, throwing away already-completed phases every time.
 *
 * <p>{@link TelegramWebhookController#attemptResume} extends that same governed capability to
 * Telegram without weakening its authority binding: it performs the identical ownership check
 * (WorkplaceContinuityRecord.humanId() must equal the requesting actor) that
 * AutonomyControlController.requireControlAuthority() enforces for the dashboard, substituting
 * Telegram's own already-trusted channel authentication for the dashboard's session bearer token --
 * it does not invent a new or weaker authority concept. These tests exercise that logic directly,
 * decoupled from Telegram transport (which makes real HTTP calls internally and is not practical to
 * unit test at this layer).
 */
final class TelegramResumeControlTest {
    private static final String HUMAN = "human-primary";
    private static final String OWNER = "worker-head";
    private static final String ORG = "org-metatron";

    @Test
    void blockedObjectiveOwnedByTheRequestingHumanIsResumed() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();
        fixture.acceptAndBlock("objective-resume-ok", now);

        TelegramWebhookController.ResumeOutcome outcome = TelegramWebhookController.attemptResume(
                fixture.management, fixture.safety, fixture.runner, fixture.workplace,
                "objective-resume-ok", HUMAN, now);

        assertEquals(TelegramWebhookController.ResumeOutcome.RESUMED, outcome);
        assertNotEquals(ManagementObjective.Status.BLOCKED,
                fixture.management.get("objective-resume-ok").status(),
                "a resumed Objective must leave BLOCKED, proving the governed resume actually ran, "
                        + "not just that this call returned without throwing");
    }

    @Test
    void resumeIsRejectedWhenTheRequestingActorDoesNotOwnTheObjective() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();
        fixture.acceptAndBlock("objective-resume-not-owned", now);

        TelegramWebhookController.ResumeOutcome outcome = TelegramWebhookController.attemptResume(
                fixture.management, fixture.safety, fixture.runner, fixture.workplace,
                "objective-resume-not-owned", "human-someone-else", now);

        assertEquals(TelegramWebhookController.ResumeOutcome.NOT_OWNED, outcome,
                "a Human who does not own this Objective must never be able to resume it -- this is "
                        + "the authority-binding check that makes exposing resume to Telegram safe");
        assertEquals(ManagementObjective.Status.BLOCKED,
                fixture.management.get("objective-resume-not-owned").status(),
                "a rejected resume must never mutate the Objective");
    }

    @Test
    void resumeIsRejectedWhenTheObjectiveIsNotInAResumableStatus() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();
        // Accepted but never blocked/paused/escalated -- e.g. still executing normally.
        fixture.accept("objective-resume-not-resumable", now);
        fixture.workplace.bindAcceptedObjective("objective-resume-not-resumable", HUMAN,
                "conversation-resume-not-resumable", "telegram", "message-resume-not-resumable",
                "request-admission:resume-not-resumable", now);

        TelegramWebhookController.ResumeOutcome outcome = TelegramWebhookController.attemptResume(
                fixture.management, fixture.safety, fixture.runner, fixture.workplace,
                "objective-resume-not-resumable", HUMAN, now);

        assertEquals(TelegramWebhookController.ResumeOutcome.NOT_RESUMABLE, outcome,
                "resume must be a no-op (never an exception surfaced to the Human as a crash) for an "
                        + "Objective that is not paused/blocked/escalated");
    }

    @Test
    void resumeIsRejectedWhenNoDurableAuthorityProvenanceExists() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();
        fixture.acceptAndBlockWithoutContinuity("objective-resume-no-provenance", now);

        TelegramWebhookController.ResumeOutcome outcome = TelegramWebhookController.attemptResume(
                fixture.management, fixture.safety, fixture.runner, fixture.workplace,
                "objective-resume-no-provenance", HUMAN, now);

        assertEquals(TelegramWebhookController.ResumeOutcome.NO_PROVENANCE, outcome);
    }

    @Test
    void humanPrefixedActorIdIsNormalizedTheSameAsTheDashboardControlPath() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();
        fixture.acceptAndBlock("objective-resume-prefixed", now);

        TelegramWebhookController.ResumeOutcome outcome = TelegramWebhookController.attemptResume(
                fixture.management, fixture.safety, fixture.runner, fixture.workplace,
                "objective-resume-prefixed", "human:" + HUMAN, now);

        assertEquals(TelegramWebhookController.ResumeOutcome.RESUMED, outcome,
                "a 'human:' prefixed actor id must normalize the same way AutonomyControlController "
                        + "already does, so the same canonical identity is recognized either way");
    }

    private static final class Fixture {
        final ManagementAutonomyService management = new ManagementAutonomyService();
        final AutonomySafetyService safety = new AutonomySafetyService();
        final AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management, (caseId, normalized, available) -> normalized.executionWorkPlan(),
                List.of(), Clock.systemUTC());
        final WorkplaceContinuityService workplace = new WorkplaceContinuityService(
                new InMemoryWorkplaceContinuityStateStore(), management, List.of(), List.of());

        void accept(String objectiveId, Instant at) {
            management.acceptHumanObjective(
                    objectiveId, OWNER, ORG, "Build a thing", HUMAN,
                    "request-admission:" + objectiveId, "case-" + objectiveId,
                    "conversation-" + objectiveId, "message-" + objectiveId, "telegram",
                    singleStepRequest(), at);
        }

        void acceptAndBlock(String objectiveId, Instant at) {
            accept(objectiveId, at);
            management.markBlocked(objectiveId, OWNER, "blocked-for-test", at);
            workplace.bindAcceptedObjective(objectiveId, HUMAN, "conversation-" + objectiveId,
                    "telegram", "message-" + objectiveId, "request-admission:" + objectiveId, at);
        }

        void acceptAndBlockWithoutContinuity(String objectiveId, Instant at) {
            accept(objectiveId, at);
            management.markBlocked(objectiveId, OWNER, "blocked-for-test", at);
        }
    }

    private static NormalizedRequest singleStepRequest() {
        ExecutionWorkSpec verify = new ExecutionWorkSpec(
                "verify", "Verify build and tests", "target",
                "execution.general.workspace", List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass"), List.of("verify evidence"));
        return new NormalizedRequest(
                "Build a thing", "target",
                List.of("execute autonomously"), IntelligenceDepth.ANALYZE,
                "evidence-backed durable work product", List.of(), List.of(), "current", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(verify), false, null, LlmProvider.OPENAI, "");
    }
}
