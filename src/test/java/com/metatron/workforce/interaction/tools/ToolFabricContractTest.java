package com.metatron.workforce.interaction.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolFabricContractTest {
    @Test
    void toolRequestFreezesArgumentsAndAuthority() {
        var request = new ToolFabric.ToolRequest(
                "worker-a", "github.read", "inspect gateway", Map.of("repo", "metatron"), List.of("read-repository"));
        assertEquals("github.read", request.capability());
        assertEquals(List.of("read-repository"), request.authorityContext());
    }

    @Test
    void blankCapabilityIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolFabric.ToolRequest("worker-a", "", "inspect", Map.of(), List.of()));
    }
}
