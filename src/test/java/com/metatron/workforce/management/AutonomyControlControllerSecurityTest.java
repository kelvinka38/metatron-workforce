package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.AnalyticalProtocolType;
import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.DeterministicCapability;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepth;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.workplace.InMemoryWorkplaceContinuityStateStore;
import com.metatron.workforce.workplace.WorkplaceContinuityService;
import com.metatron.workforce.workplace.WorkplaceDashboardAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutonomyControlControllerSecurityTest {
    @Test
    void controlRequiresAuthenticatedDelegatingHumanAndDurableAcceptanceAuthority() {
        Instant acceptedAt = Instant.parse("2026-08-31T06:00:00Z");
        ManagementAutonomyService management = new ManagementAutonomyService();
        management.acceptHumanObjective(
                "objective-security", "worker-head", "org", "secure control test",
                "human:founder", "workplace-request-admission:founder:worker-head",
                "case-security", "conversation-security", "message-security", "telegram",
                request(), acceptedAt);

        WorkplaceContinuityService workplace = new WorkplaceContinuityService(
                new InMemoryWorkplaceContinuityStateStore(), management, List.of(), List.of());
        workplace.bindAcceptedObjective(
                "objective-security", "founder", "conversation-security", "telegram",
                "message-security", "workplace-request-admission:founder:worker-head", acceptedAt);

        WorkplaceDashboardAuthService auth = new WorkplaceDashboardAuthService() {
            @Override public boolean valid(String token) { return "valid-session".equals(token); }
        };
        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management, (caseId, normalized, available) -> normalized.executionWorkPlan(),
                List.of(), Clock.systemUTC());
        AutonomyControlController controller = new AutonomyControlController(
                management, new AutonomySafetyService(), runner, workplace, auth);

        ResponseStatusException unauthenticated = assertThrows(ResponseStatusException.class, () ->
                controller.pause("objective-security", null, "founder",
                        "workplace-request-admission:founder:worker-head"));
        assertEquals(HttpStatus.UNAUTHORIZED, unauthenticated.getStatusCode());

        ResponseStatusException forgedActor = assertThrows(ResponseStatusException.class, () ->
                controller.pause("objective-security", "Bearer valid-session", "worker-head",
                        "workplace-request-admission:founder:worker-head"));
        assertEquals(HttpStatus.FORBIDDEN, forgedActor.getStatusCode());

        ResponseStatusException forgedAuthority = assertThrows(ResponseStatusException.class, () ->
                controller.pause("objective-security", "Bearer valid-session", "founder", "forged-authority"));
        assertEquals(HttpStatus.FORBIDDEN, forgedAuthority.getStatusCode());

        AutonomyControlController.ControlView paused = controller.pause(
                "objective-security", "Bearer valid-session", "human:founder",
                "workplace-request-admission:founder:worker-head");
        assertEquals(ManagementObjective.Status.PAUSED, paused.objective().status());
        assertEquals(AutonomySafetyState.ControlStatus.PAUSED, paused.safety().controlStatus());
    }

    private static NormalizedRequest request() {
        return new NormalizedRequest("objective", "target", List.of(), IntelligenceDepth.ANALYZE,
                "result", List.of(), List.of(), "current", "", IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE, List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(), false, null, LlmProvider.OPENAI, "");
    }
}
