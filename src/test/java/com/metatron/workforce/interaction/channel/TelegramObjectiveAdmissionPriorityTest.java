package com.metatron.workforce.interaction.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramObjectiveAdmissionPriorityTest {
    @Test
    void explicitObjectiveDelegationRequiresCanonicalAcceptanceBeforeProviderAck() {
        assertTrue(TelegramWebhookController.requiresObjectiveBeforeAck(
                "Take ownership of one Objective: perform a governed read-only institutional audit."));
        assertTrue(TelegramWebhookController.requiresObjectiveBeforeAck(
                "Take ownership of one governed mutation Objective in kelvinka38/metatron-workforce: repair the defect."));
    }

    @Test
    void admissionPriorityMarkerDoesNotGuessNaturalLanguageExecutionIntent() {
        assertFalse(TelegramWebhookController.requiresObjectiveBeforeAck("fix it and deploy"));
        assertFalse(TelegramWebhookController.requiresObjectiveBeforeAck("I authorize deployment"));
        assertFalse(TelegramWebhookController.requiresObjectiveBeforeAck("should we proceed"));
        assertFalse(TelegramWebhookController.requiresObjectiveBeforeAck("please audit the repositories"));
    }
}
