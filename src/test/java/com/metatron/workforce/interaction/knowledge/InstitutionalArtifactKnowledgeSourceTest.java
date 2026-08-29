package com.metatron.workforce.interaction.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class InstitutionalArtifactKnowledgeSourceTest {
    @TempDir
    Path temp;

    @Test
    void retrievesRelevantBoundedRuntimeArtifactWithoutTreatingItAsKnowledgeAdmission() throws Exception {
        Path task = temp.resolve("work-repository-audit-1");
        Files.createDirectories(task);
        Files.writeString(task.resolve("execution.json"), """
                {
                  "taskId":"work:repository-audit:1",
                  "worker":"WORKER-REPOSITORY-AUDITOR",
                  "status":"PASS",
                  "evidence":"gateway repository audit passed"
                }
                """);
        Files.writeString(temp.resolve("unrelated.json"), "{\"status\":\"ok\",\"subject\":\"payroll\"}");

        InstitutionalArtifactKnowledgeSource source = new InstitutionalArtifactKnowledgeSource(temp);
        KnowledgeDocument document = source.retrieve(new KnowledgeQuery(
                "repository audit gateway", "gateway", List.of(), 3));

        assertNotNull(document);
        assertEquals("institutional.artifacts", document.sourceId());
        assertTrue(document.content().contains("WORKER-REPOSITORY-AUDITOR"));
        assertTrue(document.evidenceReferences().getFirst().startsWith("institutional-artifact:"));
        assertFalse(document.evidenceReferences().getFirst().contains("knowledge:"));
    }

    @Test
    void doesNotReturnIrrelevantArtifact() throws Exception {
        Files.writeString(temp.resolve("execution.json"), "{\"subject\":\"payroll\",\"status\":\"PASS\"}");
        InstitutionalArtifactKnowledgeSource source = new InstitutionalArtifactKnowledgeSource(temp);
        assertNull(source.retrieve(new KnowledgeQuery("gateway repository", "gateway", List.of(), 3)));
    }
}
