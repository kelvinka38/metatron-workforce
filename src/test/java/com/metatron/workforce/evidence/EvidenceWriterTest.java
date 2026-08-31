package com.metatron.workforce.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.workers.WorkerResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceWriterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void writesMultilineEvidenceAsValidRoundTrippableJson() throws Exception {
        String evidence = "Repository Audit Report\n"
                + "source=gateway-egress/github-api\n"
                + "repository=kelvinka38/metatron-workforce\n"
                + "findings=quote=\"value\"; path=C:\\\\repo\\n"
                + "verdict=PASS\n";
        Instant completedAt = Instant.parse("2026-08-31T12:00:00Z");
        WorkerResult result = new WorkerResult("RepositoryAuditWorker", "PASS", evidence, completedAt);

        Path file = new EvidenceWriter(temporaryDirectory).write("work:repository-audit:test", result);
        assertTrue(Files.isRegularFile(file));

        JsonNode document = new ObjectMapper().findAndRegisterModules().readTree(file.toFile());
        assertEquals("work:repository-audit:test", document.path("taskId").asText());
        assertEquals("RepositoryAuditWorker", document.path("worker").asText());
        assertEquals("PASS", document.path("status").asText());
        assertEquals(evidence, document.path("evidence").asText());
        assertEquals(completedAt.toString(), document.path("completedAt").asText());
    }
}
