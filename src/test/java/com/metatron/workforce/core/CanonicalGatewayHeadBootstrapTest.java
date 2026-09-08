package com.metatron.workforce.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalGatewayHeadBootstrapTest {
    @TempDir Path temp;

    @Test
    void bootstrapsOneDurableCanonicalGatewayHeadIdempotently() throws Exception {
        WorkforceCoreStateStore store = new FileWorkforceCoreStateStore(temp.resolve("core.json"));
        WorkforceCoreService core = new WorkforceCoreService(store);
        WorkforceCoreConfiguration config = new WorkforceCoreConfiguration();

        var runner = config.canonicalGatewayHeadBootstrap(core, true);
        runner.run(null);
        runner.run(null);

        WorkforceCoreService.Worker worker = core.worker("worker:head-of-gateway:primary");
        assertEquals(WorkforceCoreService.WorkerStatus.ACTIVE, worker.status());
        assertEquals("participant:head-of-gateway", worker.participantId());
        assertEquals(1, core.participations(worker.workerId()).stream()
                .filter(p -> p.participationId().equals("participation:head-of-gateway:primary")).count());
        assertTrue(core.participations(worker.workerId()).stream().anyMatch(p ->
                p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE
                        && p.roleRef().equals("ROLE-HEAD-OF-GATEWAY")));
        assertTrue(core.capabilities(worker.workerId()).stream().anyMatch(c ->
                c.capabilityRef().equals("gateway.audit.read")));
        assertTrue(core.availability(worker.workerId()).orElseThrow().available());

        WorkforceCoreService reloaded = new WorkforceCoreService(store);
        assertEquals(WorkforceCoreService.WorkerStatus.ACTIVE,
                reloaded.worker("worker:head-of-gateway:primary").status());
    }
}
