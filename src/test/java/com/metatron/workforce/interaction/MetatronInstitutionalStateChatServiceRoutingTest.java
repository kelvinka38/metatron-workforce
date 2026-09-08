package com.metatron.workforce.interaction;

import com.metatron.workforce.workplace.WorkplaceDashboardService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

final class MetatronInstitutionalStateChatServiceRoutingTest {
    private static final String GO1 =
            "Take ownership of one governed general engineering Objective against kelvinka38/metatron-workforce "
                    + "using execution.general.workspace. Find the root cause yourself. Fix the defect, run tests, "
                    + "create one local Git commit, publish an unmerged GitHub pull request, verify through Observation. Do not merge.";

    @Test
    void explicitObjectiveControlCannotBeInterceptedAsInstitutionalStateQuestion() {
        WorkplaceDashboardService dashboard = mock(WorkplaceDashboardService.class);
        MetatronInstitutionalStateChatService service = new MetatronInstitutionalStateChatService(dashboard);

        assertTrue(service.answer(GO1).isEmpty());
        verifyNoInteractions(dashboard);
    }
}
