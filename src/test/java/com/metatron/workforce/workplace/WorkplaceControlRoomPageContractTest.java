package com.metatron.workforce.workplace;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class WorkplaceControlRoomPageContractTest {
    @Test
    void controlRoomPageExposesWorkerTaskProjectAndManagementSurfaces() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("static/workplace/control-room.html")) {
            assertNotNull(in, "Control Room HTML must be packaged");
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(html.contains("METATRON WORKPLACE"));
            assertTrue(html.contains("data-view=\"workers\""));
            assertTrue(html.contains("data-view=\"tasks\""));
            assertTrue(html.contains("data-view=\"projects\""));
            assertTrue(html.contains("/workplace/api/control-room"));
            assertTrue(html.contains("/chat"));
            assertTrue(html.contains("/profile"));
            assertTrue(html.contains("Runtime & Tools"));
            assertTrue(html.contains("Memory"));
            assertTrue(html.contains("Live cognition instructions"));
            assertTrue(html.contains("Worker-model completeness gaps"));
            assertTrue(html.contains("/availability"));
            assertTrue(html.contains("/control/"));
            assertTrue(html.contains("Evidence drill-down"));
            assertFalse(html.contains("Objectives & task execution"),
                    "old telemetry-only dashboard must not remain the product surface");
        }
    }
}
