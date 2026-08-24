package com.metatron.workforce.interaction.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultToolFabricTest {
    @Test
    void routesCapabilityWithoutInvokingAnLlm() {
        ToolAdapter adapter = new ToolAdapter() {
            public String capability() { return "github.read"; }
            public ToolResult execute(ToolRequest request) {
                return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(), true, "commit abc", List.of("github://commit/abc"));
            }
        };
        DefaultToolFabric fabric = new DefaultToolFabric(List.of(adapter));

        var result = fabric.execute(new ToolRequest("tool-1", "worker-1", "github.read", "repo", "read", "main", List.of("read-only")));

        assertEquals("commit abc", result.output());
        assertEquals("github://commit/abc", result.evidenceReferences().getFirst());
    }
}
