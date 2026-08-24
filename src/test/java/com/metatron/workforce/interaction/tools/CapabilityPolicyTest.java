package com.metatron.workforce.interaction.tools;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityPolicyTest {
    @Test
    void deniesByDefault() {
        CapabilityPolicy policy = new CapabilityPolicy(Set.of("github.read"));
        assertTrue(policy.allows("github.read"));
        assertFalse(policy.allows("cloudflare.write"));
    }

    @Test
    void fabricRejectsDeniedCapabilityBeforeDispatch() {
        ToolAdapter adapter = new ToolAdapter() {
            public String capability() { return "github.read"; }
            public ToolResult execute(ToolRequest request) { return ToolResult.success("called"); }
        };
        PolicyToolFabric fabric = new PolicyToolFabric(Map.of("github.read", adapter),
                new CapabilityPolicy(Set.of()));
        ToolRequest request = new ToolRequest(
                "test-request",
                "test",
                "github.read",
                "/repos/x/y",
                "read",
                "",
                List.of());
        assertEquals("capability_denied", fabric.execute(request).message());
    }
}
