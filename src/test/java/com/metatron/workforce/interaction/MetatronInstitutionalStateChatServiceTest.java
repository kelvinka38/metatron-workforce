package com.metatron.workforce.interaction;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.management.ManagementAutonomyService;
import com.metatron.workforce.work.WorkService;
import com.metatron.workforce.workplace.WorkplaceDashboardService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetatronInstitutionalStateChatServiceTest {
    @Test
    void metatronWorkforceQuestionUsesCanonicalInternalStateInsteadOfPublicWorld() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("P-HEAD", WorkforceCoreService.ParticipantType.AI, "admission:head");
        core.admitWorker("WORKER-METATRON-HEAD", "P-HEAD");
        core.recognizeParticipant("P-GEN", WorkforceCoreService.ParticipantType.AI, "admission:general");
        core.admitWorker("WORKER-GENERAL-ENGINEERING", "P-GEN");

        MetatronInstitutionalStateChatService service = new MetatronInstitutionalStateChatService(
                new WorkplaceDashboardService(core, new ManagementAutonomyService(), new WorkService()));

        String answer = service.answer("How many workforce of Metatron do we have now?").orElseThrow();

        assertTrue(answer.contains("Workers: 2 active / 2 total admitted"));
        assertTrue(answer.contains("WORKER-METATRON-HEAD"));
        assertTrue(answer.contains("WORKER-GENERAL-ENGINEERING"));
        assertTrue(answer.contains("source=canonical-workplace-state"));
        assertFalse(answer.contains("Defense Acquisition"));
    }

    @Test
    void unrelatedPublicQuestionIsNotCaptured() {
        MetatronInstitutionalStateChatService service = new MetatronInstitutionalStateChatService(
                new WorkplaceDashboardService(new WorkforceCoreService(), new ManagementAutonomyService(), new WorkService()));
        assertTrue(service.answer("How many workers are in the US Army?").isEmpty());
        assertTrue(service.answer("What is Metatron architecture?").isEmpty());
    }
}
