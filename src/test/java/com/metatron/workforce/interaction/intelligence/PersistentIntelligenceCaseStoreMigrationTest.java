package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class PersistentIntelligenceCaseStoreMigrationTest {
    @TempDir
    Path tempDir;

    @Test
    void lazilyMigratesLegacyConversationKeyedCaseWithoutLosingIdentityOrEvidence() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String conversationId = "conversation:legacy-production";
        Instant now = Instant.parse("2026-08-30T00:00:00Z");
        IntelligenceCase legacy = new IntelligenceCase(
                "case-legacy-1", conversationId, "human:primary", "legacy objective",
                IntelligenceDepth.ANALYZE, IntelligenceCaseStatus.RESULT_READY,
                List.of(), List.of("evidence:legacy"), List.of(), List.of(), List.of(), List.of(), List.of(),
                "legacy conclusion", "", List.of("execution:legacy"), now, now);

        Path legacyFile = tempDir.resolve(hash(conversationId) + ".json");
        Files.writeString(legacyFile, mapper.writeValueAsString(legacy), StandardCharsets.UTF_8);

        PersistentIntelligenceCaseStore store = new PersistentIntelligenceCaseStore(tempDir, mapper);
        IntelligenceCase loaded = store.findActive(conversationId).orElseThrow();

        assertEquals(legacy.caseId(), loaded.caseId());
        assertEquals(List.of("evidence:legacy"), loaded.evidenceReferences());
        assertEquals("legacy conclusion", loaded.latestConclusion());
        assertEquals("case-legacy-1", store.findByCaseId("case-legacy-1").orElseThrow().caseId());
        assertTrue(Files.isDirectory(tempDir.resolve("cases")));
        assertTrue(Files.isDirectory(tempDir.resolve("by-conversation")));
        assertTrue(Files.exists(legacyFile), "legacy source is retained for rollback/audit compatibility");
    }

    private static String hash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
