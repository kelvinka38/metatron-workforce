package com.metatron.workforce.interaction.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentEntityWebSearchRecoveryAdapterTest {

    private static final String PRODUCTION_OBJECTIVE =
            "Xác định Tổng thống hiện tại của Indonesia bằng cách kiểm tra nguồn thông tin hiện tại";

    @Test
    void stripsInstructionVerbsFromLiteralProductionObjective() {
        Set<String> subject = CurrentEntityWebSearchRecoveryAdapter.subjectTokens(PRODUCTION_OBJECTIVE);

        assertTrue(subject.contains("president"));
        assertTrue(subject.contains("indonesia"));
        assertFalse(subject.contains("xac"));
        assertFalse(subject.contains("dinh"));
        assertFalse(subject.contains("determine"));
        assertFalse(subject.contains("bang"));
        assertFalse(subject.contains("cach"));
        assertEquals("current president indonesia",
                CurrentEntityWebSearchRecoveryAdapter.compactCurrentQuery(PRODUCTION_OBJECTIVE, subject));
    }

    @Test
    void rejectsDictionaryEvidenceThatOnlyMatchesInstructionWords() {
        ToolAdapter dictionaryDelegate = adapter(request -> new ToolResult(
                request.requestId(), request.capability(), request.target(), request.operation(), true,
                "query=" + request.input() + "\n"
                        + "answer=Prabowo Subianto is President of Indonesia\n"
                        + "source_excerpt=xác: danh từ. định nghĩa và cách dùng của từ xác.",
                List.of("https://vi.wiktionary.org/wiki/xac")));
        CurrentEntityWebSearchRecoveryAdapter recovery = new CurrentEntityWebSearchRecoveryAdapter(dictionaryDelegate);

        ToolResult result = recovery.execute(request(PRODUCTION_OBJECTIVE));

        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("source_identity_rejected"), result.output());
        assertTrue(result.evidenceReferences().isEmpty());
    }

    @Test
    void admitsEvidenceWhoseSourceBodyContainsRelationAndEntity() {
        ToolAdapter relevantDelegate = adapter(request -> new ToolResult(
                request.requestId(), request.capability(), request.target(), request.operation(), true,
                "query=" + request.input() + "\n"
                        + "answer=Prabowo Subianto is the current President of Indonesia\n"
                        + "source_excerpt=Indonesia President Prabowo Subianto serves as the current head of state.",
                List.of("https://example.go.id/current-president")));
        CurrentEntityWebSearchRecoveryAdapter recovery = new CurrentEntityWebSearchRecoveryAdapter(relevantDelegate);

        ToolResult result = recovery.execute(request(PRODUCTION_OBJECTIVE));

        assertTrue(result.success(), result.output());
        assertEquals(List.of("https://example.go.id/current-president"), result.evidenceReferences());
        assertTrue(result.output().contains("discovery_query=current president indonesia"));
    }

    @Test
    void fabricPrioritizesCurrentEntityRecoveryOverRawInstructionHeavySearch() {
        AtomicInteger rawCalls = new AtomicInteger();
        ToolAdapter raw = adapter(request -> {
            rawCalls.incrementAndGet();
            return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(), true,
                    "query=" + request.input() + "\nsource_excerpt=xác định nghĩa từ điển",
                    List.of("https://dictionary.example/xac"));
        });
        ToolAdapter recoveredSource = adapter(request -> new ToolResult(
                request.requestId(), request.capability(), request.target(), request.operation(), true,
                "query=" + request.input() + "\nsource_excerpt=President of Indonesia Prabowo Subianto.",
                List.of("https://example.go.id/president")));
        CurrentEntityWebSearchRecoveryAdapter recovery = new CurrentEntityWebSearchRecoveryAdapter(recoveredSource);
        DefaultToolFabric fabric = new DefaultToolFabric(List.of(raw, recovery));

        ToolResult result = fabric.execute(request(PRODUCTION_OBJECTIVE));

        assertTrue(result.success(), result.output());
        assertEquals(0, rawCalls.get(), "raw instruction-heavy query must not outrank entity recovery");
        assertEquals(List.of("https://example.go.id/president"), result.evidenceReferences());
    }

    @Test
    void fabricPrioritizesSubjectFocusedVersionRecoveryBeforeGeneralSearch() {
        AtomicInteger rawCalls = new AtomicInteger();
        ToolAdapter raw = adapter(request -> {
            rawCalls.incrementAndGet();
            return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(), true,
                    "query=" + request.input() + "\nsource_excerpt=Python 3.12 online compiler documentation.",
                    List.of("https://online-python.example/"));
        });
        ToolAdapter authoritativeDelegate = adapter(request -> new ToolResult(
                request.requestId(), request.capability(), request.target(), request.operation(), true,
                "query=" + request.input() + "\nsource_excerpt=Python 3.14.2 is the latest stable Python release.",
                List.of("https://www.python.org/downloads/")));
        SubjectOnlyVersionWebSearchRecoveryAdapter versionRecovery =
                new SubjectOnlyVersionWebSearchRecoveryAdapter(authoritativeDelegate);
        DefaultToolFabric fabric = new DefaultToolFabric(List.of(raw, versionRecovery));

        ToolResult result = fabric.execute(request("Python latest stable version"));

        assertTrue(result.success(), result.output());
        assertEquals(0, rawCalls.get(), "subject-focused version recovery must run before general search");
        assertEquals(List.of("https://www.python.org/downloads/"), result.evidenceReferences());
        assertTrue(result.output().contains("Python 3.14.2"));
    }

    private static ToolRequest request(String input) {
        return new ToolRequest("point2-current-entity", "human:founder", WebSearchToolAdapter.CAPABILITY,
                "internet:web-search", "search", input, List.of("read-only"));
    }

    private static ToolAdapter adapter(java.util.function.Function<ToolRequest, ToolResult> function) {
        return new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }
            @Override public ToolResult execute(ToolRequest request) { return function.apply(request); }
        };
    }
}
