package com.metatron.workforce.evidence;

import com.metatron.workforce.workers.WorkerResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class EvidenceWriter {

    private final Path root;

    public EvidenceWriter() {
        this(Path.of(env("METATRON_RUNTIME_EVIDENCE_DIR", "runtime-evidence")));
    }

    EvidenceWriter(Path root) {
        this.root = root;
    }

    public Path write(
            String taskId,
            WorkerResult result
    ) throws IOException {

        Path directory = root.resolve(safePathSegment(taskId));
        Files.createDirectories(directory);

        Path file = directory.resolve("execution.json");

        String json =
                """
                {
                  "taskId": "%s",
                  "worker": "%s",
                  "status": "%s",
                  "evidence": "%s",
                  "completedAt": "%s"
                }
                """.formatted(
                    taskId,
                    result.worker(),
                    result.status(),
                    result.evidence().replace("\"","'"),
                    result.completedAt()
                );

        Files.writeString(file, json);
        return file;
    }

    private static String safePathSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("taskId required");
        }
        return value.trim().replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
