package com.metatron.workforce.interaction.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void nominalOffTopicWebSuccessFallsThroughToSemanticallyAdmissibleRecovery() {
        AtomicInteger calls = new AtomicInteger();
        ToolAdapter irrelevantPrimary = adapter(request -> {
            calls.incrementAndGet();
            return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(), true,
                    "query=Python latest stable version\nBest food in Vietnam and regional dishes",
                    List.of("https://foodvietnamese.com/best-food-in-vietnam/"));
        });
        ToolAdapter relevantRecovery = adapter(request -> {
            calls.incrementAndGet();
            return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(), true,
                    "query=Python latest stable version\nPython 3.14.2 is the current stable Python release.",
                    List.of("https://www.python.org/downloads/"));
        });
        DefaultToolFabric fabric = new DefaultToolFabric(List.of(irrelevantPrimary, relevantRecovery));
        ToolRequest request = new ToolRequest("web-1", "intelligence", WebSearchToolAdapter.CAPABILITY,
                "internet:web-search", "search", "Python latest stable version", List.of());

        ToolResult result = fabric.execute(request);

        assertTrue(result.success());
        assertEquals(2, calls.get());
        assertEquals(List.of("https://www.python.org/downloads/"), result.evidenceReferences());
        assertFalse(result.output().contains("foodvietnamese"));
    }

    @Test
    void multilingualSourceQualifierIsNotMistakenForVersionSubjectIdentity() {
        ToolAdapter relevant = adapter(request -> new ToolResult(
                request.requestId(), request.capability(), request.target(), request.operation(), true,
                "query=Python official latest stable version download release\n"
                        + "Python 3.14.7 is the current stable release available for download.",
                List.of("https://www.python.org/downloads/")));
        DefaultToolFabric fabric = new DefaultToolFabric(List.of(relevant));
        ToolRequest request = new ToolRequest("web-vn", "intelligence", WebSearchToolAdapter.CAPABILITY,
                "internet:web-search", "search", "Python trực tuyến latest stable version", List.of());

        ToolResult result = fabric.execute(request);

        assertTrue(result.success(), result.output());
        assertEquals(List.of("https://www.python.org/downloads/"), result.evidenceReferences());
    }

    @Test
    void shortVersionSubjectStillUsesLegacyFallback() {
        ToolAdapter relevant = adapter(request -> new ToolResult(
                request.requestId(), request.capability(), request.target(), request.operation(), true,
                "Go 1.25.1 is the current stable release.",
                List.of("https://go.dev/dl/")));
        DefaultToolFabric fabric = new DefaultToolFabric(List.of(relevant));
        ToolRequest request = new ToolRequest("web-go", "intelligence", WebSearchToolAdapter.CAPABILITY,
                "internet:web-search", "search", "Go latest stable version", List.of());

        ToolResult result = fabric.execute(request);

        assertTrue(result.success(), result.output());
        assertEquals(List.of("https://go.dev/dl/"), result.evidenceReferences());
    }

    @Test
    void qualifierSensitiveVersionQueryFailsClosedWhenEverySuccessfulResultIsOffTopic() {
        ToolAdapter irrelevantOne = adapter(request -> new ToolResult(
                request.requestId(), request.capability(), request.target(), request.operation(), true,
                "query=Visual Studio Code latest stable version\nVietnam travel guide 3.5",
                List.of("https://example.com/vietnam-travel")));
        ToolAdapter irrelevantTwo = adapter(request -> new ToolResult(
                request.requestId(), request.capability(), request.target(), request.operation(), true,
                "query=Visual Studio Code latest stable version\nCooking release 2.1",
                List.of("https://example.com/cooking")));
        DefaultToolFabric fabric = new DefaultToolFabric(List.of(irrelevantOne, irrelevantTwo));
        ToolRequest request = new ToolRequest("web-2", "intelligence", WebSearchToolAdapter.CAPABILITY,
                "internet:web-search", "search", "Visual Studio Code latest stable version", List.of());

        ToolResult result = fabric.execute(request);

        assertFalse(result.success());
        assertTrue(result.evidenceReferences().isEmpty());
        assertTrue(result.output().contains("semantic_evidence_rejected"));
    }

    @Test
    void nonQualifierWebQueriesKeepExistingFabricSemantics() {
        ToolAdapter primary = adapter(request -> new ToolResult(
                request.requestId(), request.capability(), request.target(), request.operation(), true,
                "ordinary search payload", List.of("https://example.com/result")));
        DefaultToolFabric fabric = new DefaultToolFabric(List.of(primary));
        ToolRequest request = new ToolRequest("web-3", "intelligence", WebSearchToolAdapter.CAPABILITY,
                "internet:web-search", "search", "weather Ho Chi Minh City", List.of());

        ToolResult result = fabric.execute(request);

        assertTrue(result.success());
        assertEquals(List.of("https://example.com/result"), result.evidenceReferences());
    }

    private static ToolAdapter adapter(java.util.function.Function<ToolRequest, ToolResult> function) {
        return new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }
            @Override public ToolResult execute(ToolRequest request) { return function.apply(request); }
        };
    }
}
