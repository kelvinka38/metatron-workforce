package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.phase9.BoundaryProvenance;
import com.metatron.workforce.phase9.BoundaryResult;
import com.metatron.workforce.phase9.BoundaryStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class IntelligenceExecutionAdmissionServiceTest {
    @Test
    void createsHandoffOnlyAfterSuccessfulAuthorizationAndGatewayBoundaries() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-30T04:00:00Z"), ZoneOffset.UTC);
        IntelligenceExecutionAdmissionService service = new IntelligenceExecutionAdmissionService(clock);
        IntelligenceCase intelligenceCase = baseCase();
        BoundaryResult authorization = success(
                InstitutionalIntelligenceReferenceBridge.AUTHORIZATION_CONTRACT,
                "authority:auth-1", "authorization-output");
        BoundaryResult gateway = success(
                InstitutionalIntelligenceReferenceBridge.GATEWAY_CONTRACT,
                "authority:gateway", "gateway-output");

        var result = service.admit(
                intelligenceCase,
                "worker-a",
                "assignment-1",
                "work-package-1",
                "execution-1",
                "authority:auth-1",
                "gateway:request-1",
                authorization,
                gateway);

        assertEquals("execution-1", result.handoff().executionId());
        assertEquals("assignment-1", result.handoff().assignmentId());
        assertEquals("authority:auth-1", result.handoff().authorizationId());
        assertEquals(IntelligenceCaseStatus.WAITING_ON_EXTERNAL_STATE, result.intelligenceCase().status());
        assertTrue(result.intelligenceCase().externalInstitutionalReferences().contains("assignment:assignment-1"));
        assertTrue(result.intelligenceCase().externalInstitutionalReferences().contains("authority:auth-1"));
        assertTrue(result.intelligenceCase().externalInstitutionalReferences().contains("gateway:request-1"));
    }

    @Test
    void failsClosedWhenAuthorizationBoundaryIsDenied() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-30T04:00:00Z"), ZoneOffset.UTC);
        IntelligenceExecutionAdmissionService service = new IntelligenceExecutionAdmissionService(clock);
        BoundaryProvenance provenance = provenance();
        BoundaryResult denied = new BoundaryResult(
                "req-auth", InstitutionalIntelligenceReferenceBridge.AUTHORIZATION_CONTRACT,
                BoundaryStatus.DENIED, null, "authority:auth-1", provenance);
        BoundaryResult gateway = success(
                InstitutionalIntelligenceReferenceBridge.GATEWAY_CONTRACT,
                "authority:gateway", "gateway-output");

        assertThrows(SecurityException.class, () -> service.admit(
                baseCase(), "worker-a", "assignment-1", "work-package-1", "execution-1",
                "authority:auth-1", "gateway:request-1", denied, gateway));
    }

    @Test
    void refusesAuthorizationReferenceThatDoesNotMatchBoundaryAuthority() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-30T04:00:00Z"), ZoneOffset.UTC);
        IntelligenceExecutionAdmissionService service = new IntelligenceExecutionAdmissionService(clock);
        BoundaryResult authorization = success(
                InstitutionalIntelligenceReferenceBridge.AUTHORIZATION_CONTRACT,
                "authority:real", "authorization-output");
        BoundaryResult gateway = success(
                InstitutionalIntelligenceReferenceBridge.GATEWAY_CONTRACT,
                "authority:gateway", "gateway-output");

        assertThrows(SecurityException.class, () -> service.admit(
                baseCase(), "worker-a", "assignment-1", "work-package-1", "execution-1",
                "authority:forged", "gateway:request-1", authorization, gateway));
    }

    private static BoundaryResult success(String contract, String authority, Object output) {
        return new BoundaryResult("req-" + contract, contract, BoundaryStatus.SUCCESS, output, authority, provenance());
    }

    private static BoundaryProvenance provenance() {
        return new BoundaryProvenance("phase9", "evidence:boundary", Instant.parse("2026-08-30T04:00:00Z"));
    }

    private static IntelligenceCase baseCase() {
        Instant now = Instant.parse("2026-08-30T03:00:00Z");
        return new IntelligenceCase(
                "case-exec", "conversation:1", "human:1", "execute approved objective",
                IntelligenceDepth.ANALYZE, IntelligenceCaseStatus.RESULT_READY,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "recommend execution", "", List.of(), now, now);
    }
}
