package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.operating.WorkerConstitutionRuntimeMaterializer;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class GatewayDirectorBootReconciliationTest {
    @Test
    void staffingFailureIsRecordedAsDegradedAndDoesNotEscapeApplicationRunner() {
        AutonomousStaffingService staffing = mock(AutonomousStaffingService.class);
        doThrow(new IllegalStateException("POLICY_IDENTITY_CONFLICT"))
                .when(staffing).ensureStaffed(any(), any());

        GatewayDirectorBootstrapStatus status = new GatewayDirectorBootstrapStatus();
        ApplicationRunner runner = new LiveManagementConfiguration().canonicalGatewayDirectorReconciliation(
                staffing,
                mock(GatewayDirectorAppointmentCapability.class),
                mock(WorkforceCoreService.class),
                mock(RuntimeCapacityCoordinator.class),
                mock(WorkerConstitutionRuntimeMaterializer.class),
                status,
                true,
                300);

        assertDoesNotThrow(() -> runner.run(null));
        assertEquals(GatewayDirectorBootstrapStatus.State.DEGRADED, status.snapshot().state());
        assertTrue(status.snapshot().detail().contains("POLICY_IDENTITY_CONFLICT"));
    }
}
