package com.metatron.workforce.interaction.routing;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class IntentRoutingTest {
    @Test
    void parsesAuditTarget() {
        Intent intent = new IntentParser().parse("audit G4 gateway");
        assertEquals("audit", intent.action());
        assertEquals("G4 gateway", intent.target());
    }

    @Test
    void routesOnlyExplicitTargets() {
        WorkerRouter router = new WorkerRouter(Map.of("audit:g4 gateway", "gateway-auditor"));
        WorkerRoute route = router.route(new Intent("audit", "G4 gateway", "audit G4 gateway"));
        assertEquals("gateway-auditor", route.workerId());
        assertThrows(IllegalArgumentException.class,
                () -> router.route(new Intent("audit", "unknown", "audit unknown")));
    }
}
