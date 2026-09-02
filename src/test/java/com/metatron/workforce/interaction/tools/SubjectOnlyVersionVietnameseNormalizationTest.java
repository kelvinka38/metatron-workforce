package com.metatron.workforce.interaction.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubjectOnlyVersionVietnameseNormalizationTest {
    @Test
    void extractsOnlyPythonFromFounderVietnameseQuery() {
        assertEquals(Set.of("python"), SubjectOnlyVersionWebSearchRecoveryAdapter.subjectTokens(
                "Phiên bản stable mới nhất của Python hiện tại là gì? Kiểm tra nguồn hiện tại rồi trả lời."));
    }

    @Test
    void extractsOnlyPythonFromNormalizedVietnameseRequirementAndRecoversVersion() {
        AtomicReference<String> delegatedQuery = new AtomicReference<>();
        ToolAdapter delegate = new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }
            @Override public ToolResult execute(ToolRequest request) {
                delegatedQuery.set(request.input());
                return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(), true,
                        "WEB SEARCH RESULTS\nquery=python\nsource_excerpt=Python 3.14.7 is available from python.org.",
                        List.of("https://www.python.org/downloads/"));
            }
        };
        SubjectOnlyVersionWebSearchRecoveryAdapter adapter = new SubjectOnlyVersionWebSearchRecoveryAdapter(delegate);
        String requirement = "Xác định phiên bản stable mới nhất của Python hiện tại từ nguồn trực tuyến";

        ToolResult result = adapter.execute(new ToolRequest(
                "vn-normalized", "human:test", WebSearchToolAdapter.CAPABILITY,
                "internet:web-search", "search", requirement, List.of("read-only")));

        assertTrue(result.success(), result.output());
        assertEquals("python", delegatedQuery.get());
        assertEquals(Set.of("python"), SubjectOnlyVersionWebSearchRecoveryAdapter.subjectTokens(requirement));
    }
}
