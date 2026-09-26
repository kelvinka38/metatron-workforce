package com.metatron.workforce.interaction.channel;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Production 2026-09-26 (update 103338033): a DISCUSSION answer written by the model quoted an earlier Objective as
 * {@code objective_id=`objective:…:103338032`}. The controller took that line as a newly admitted Objective,
 * tried to render a Work card for the backticked id, failed with "unknown objective" three times and dead-lettered
 * the Founder's message. An {@code objective_id=} line only means "this interaction admitted an Objective" when the
 * Objective exists after the interaction and did not exist before it.
 */
class TelegramAnswerObjectiveReferenceTest {
    private static final String PREVIOUS =
            "objective:intelligence-case:case-e1c8b586-8774-4dee-a585-e4a2b0a87890:request:telegram:update:103338032";
    private static final String ADMITTED =
            "objective:intelligence-case:case-e1c8b586-8774-4dee-a585-e4a2b0a87890:request:telegram:update:103338033";

    @Test
    void modelQuotedObjectiveIdInBackticksIsNotAnAdmission() {
        String answer = "Objective trước đã được tiếp nhận nhưng bị chặn ở bước 1.\n"
                + "objective_id=`" + PREVIOUS + "`\n"
                + "Bạn có thể gửi lại yêu cầu bắt đầu bằng HOA:.";
        assertEquals("", TelegramWebhookController.admittedObjectiveId(answer, Set.of(PREVIOUS), Set.of(PREVIOUS)));
    }

    @Test
    void echoOfAnObjectiveThatExistedBeforeThisInteractionIsNotAnAdmission() {
        String answer = "Trạng thái công việc trước:\nobjective_id=" + PREVIOUS + "\n";
        assertEquals("", TelegramWebhookController.admittedObjectiveId(answer, Set.of(PREVIOUS), Set.of(PREVIOUS)));
    }

    @Test
    void unknownObjectiveIdIsNotAnAdmission() {
        String answer = "objective_id=objective:does-not-exist\n";
        assertEquals("", TelegramWebhookController.admittedObjectiveId(answer, Set.of(), Set.of(PREVIOUS)));
    }

    @Test
    void objectiveAdmittedByThisInteractionIsRecognized() {
        String answer = "Workforce accepted the Objective.\nobjective_id=" + ADMITTED + "\nowner=WORKER-HEAD";
        assertEquals(ADMITTED, TelegramWebhookController.admittedObjectiveId(
                answer, Set.of(PREVIOUS), Set.of(PREVIOUS, ADMITTED)));
    }

    @Test
    void answerWithoutObjectiveLineAdmitsNothing() {
        assertEquals("", TelegramWebhookController.admittedObjectiveId("Chỉ là thảo luận.", Set.of(), Set.of(ADMITTED)));
    }
}
