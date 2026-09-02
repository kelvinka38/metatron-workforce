package com.metatron.workforce.interaction.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubjectOnlyVersionWebSearchRecoveryAdapterTest {
    @Test
    void searchesOnlySubjectButReturnsEvidenceForOriginalVersionRequirement() {
        AtomicReference<String> delegatedQuery = new AtomicReference<>();
        ToolAdapter delegate = new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }
            @Override public ToolResult execute(ToolRequest request) {
                delegatedQuery.set(request.input());
                return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(), true,
                        "WEB SEARCH RESULTS\nquery=python\nsource_excerpt=Python 3.14.7 is now available for download.",
                        List.of("https://www.python.org/downloads/"));
            }
        };
        SubjectOnlyVersionWebSearchRecoveryAdapter adapter = new SubjectOnlyVersionWebSearchRecoveryAdapter(delegate);

        ToolResult result = adapter.execute(request("Python latest stable version"));

        assertTrue(result.success(), result.output());
        assertEquals("python", delegatedQuery.get());
        assertEquals(List.of("https://www.python.org/downloads/"), result.evidenceReferences());
        assertTrue(result.output().contains("Python 3.14.7"));
    }

    @Test
    void recoversVietnameseVersionRequirementUsingOnlyRealSubjectIdentity() {
        AtomicReference<String> delegatedQuery = new AtomicReference<>();
        ToolAdapter delegate = new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }
            @Override public ToolResult execute(ToolRequest request) {
                delegatedQuery.set(request.input());
                return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(), true,
                        "WEB SEARCH RESULTS\nquery=python\nsource_excerpt=Download Python 3.14.7 from the official Python website.",
                        List.of("https://www.python.org/downloads/"));
            }
        };
        SubjectOnlyVersionWebSearchRecoveryAdapter adapter = new SubjectOnlyVersionWebSearchRecoveryAdapter(delegate);

        ToolResult result = adapter.execute(request(
                "Phiên bản stable mới nhất của Python hiện tại là gì? Kiểm tra nguồn hiện tại rồi trả lời."));

        assertTrue(result.success(), result.output());
        assertEquals("python", delegatedQuery.get());
        assertEquals(Set.of("python"), SubjectOnlyVersionWebSearchRecoveryAdapter.subjectTokens(
                "Phiên bản stable mới nhất của Python hiện tại là gì? Kiểm tra nguồn hiện tại rồi trả lời."));
        assertTrue(result.output().contains("Python 3.14.7"));
    }

    @Test
    void remainsDomainIndependentForMultiTokenSoftwareSubjects() {
        AtomicReference<String> delegatedQuery = new AtomicReference<>();
        ToolAdapter delegate = new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }
            @Override public ToolResult execute(ToolRequest request) {
                delegatedQuery.set(request.input());
                return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(), true,
                        "WEB SEARCH RESULTS\nsource_excerpt=Visual Studio Code 1.105.1 is available.",
                        List.of("https://code.visualstudio.com/updates/"));
            }
        };
        SubjectOnlyVersionWebSearchRecoveryAdapter adapter = new SubjectOnlyVersionWebSearchRecoveryAdapter(delegate);

        ToolResult result = adapter.execute(request("Visual Studio Code latest stable version"));

        assertTrue(result.success(), result.output());
        assertEquals("visual studio code", delegatedQuery.get());
        assertTrue(result.output().contains("1.105.1"));
    }

    @Test
    void rejectsNominalDelegateSuccessWhenSubjectIsMissing() {
        ToolAdapter delegate = new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }
            @Override public ToolResult execute(ToolRequest request) {
                return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(), true,
                        "WEB SEARCH RESULTS\nsource_excerpt=Java 25.0.1 is available.",
                        List.of("https://example.com/java"));
            }
        };
        SubjectOnlyVersionWebSearchRecoveryAdapter adapter = new SubjectOnlyVersionWebSearchRecoveryAdapter(delegate);

        ToolResult result = adapter.execute(request("Python latest stable version"));

        assertFalse(result.success());
        assertTrue(result.output().contains("subject_rejected"), result.output());
    }

    @Test
    void rejectsSubjectRelevantEvidenceWithoutConcreteVersion() {
        ToolAdapter delegate = new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }
            @Override public ToolResult execute(ToolRequest request) {
                return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(), true,
                        "WEB SEARCH RESULTS\nsource_excerpt=Python downloads and documentation.",
                        List.of("https://www.python.org/downloads/"));
            }
        };
        SubjectOnlyVersionWebSearchRecoveryAdapter adapter = new SubjectOnlyVersionWebSearchRecoveryAdapter(delegate);

        ToolResult result = adapter.execute(request("Python latest stable version"));

        assertFalse(result.success());
        assertTrue(result.output().contains("answer_shape_rejected"), result.output());
    }

    private static ToolRequest request(String query) {
        return new ToolRequest("subject-recovery", "human:test", WebSearchToolAdapter.CAPABILITY,
                "internet:web-search", "search", query, List.of("read-only"));
    }
}
