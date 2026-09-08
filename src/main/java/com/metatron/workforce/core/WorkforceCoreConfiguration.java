package com.metatron.workforce.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class WorkforceCoreConfiguration {
    @Bean
    WorkforceCoreStateStore workforceCoreStateStore(
            @Value("${METATRON_WORKFORCE_CORE_STATE_PATH:/var/lib/metatron-workforce/workforce-core-state.json}") String configured) {
        return new FileWorkforceCoreStateStore(Path.of(configured));
    }

    @Bean
    WorkforceCoreService workforceCoreService(WorkforceCoreStateStore store) {
        return new WorkforceCoreService(store);
    }

    /**
     * Production bootstrap for the already-defined canonical Gateway Head institutional role.
     * Identity is stable and durable; this does not create a transient persona.
     */
    @Bean
    ApplicationRunner canonicalGatewayHeadBootstrap(
            WorkforceCoreService core,
            @Value("${METATRON_BOOTSTRAP_GATEWAY_HEAD:true}") boolean enabled) {
        return args -> {
            if (!enabled) return;
            final String participantId = "participant:head-of-gateway";
            final String workerId = "worker:head-of-gateway:primary";
            final String participationId = "participation:head-of-gateway:primary";
            final String organizationRef = "organization:metatron";
            final String positionRef = "POSITION-HEAD-OF-GATEWAY";
            final String roleRef = "ROLE-HEAD-OF-GATEWAY";

            core.recognizeParticipant(participantId, WorkforceCoreService.ParticipantType.AI,
                    "institutional-bootstrap:gateway-head:v1");
            WorkforceCoreService.Worker worker = core.admitWorker(workerId, participantId);
            if (worker.status() == WorkforceCoreService.WorkerStatus.ACTIVE
                    && core.participations(workerId).stream().noneMatch(p -> participationId.equals(p.participationId()))) {
                core.participate(participationId, workerId, organizationRef, positionRef, roleRef);
            }
            if (worker.status() == WorkforceCoreService.WorkerStatus.ACTIVE
                    && core.capabilities(workerId).stream().noneMatch(c -> "gateway.audit.read".equals(c.capabilityRef()))) {
                core.attestCapability(workerId, "gateway.audit.read", 1.0,
                        "institutional-bootstrap:gateway-head-capability:v1");
            }
            if (worker.status() == WorkforceCoreService.WorkerStatus.ACTIVE
                    && core.availability(workerId).isEmpty()) {
                core.setAvailability(workerId, true, 1.0);
            }
        };
    }
}
