package com.metatron.workforce.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.workers.WorkerResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Writes canonical runtime evidence as syntactically valid JSON. */
public class EvidenceWriter {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
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
        Path temporary = directory.resolve("execution.json.tmp");

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("taskId", taskId);
        document.put("worker", result.worker());
        document.put("status", result.status());
        document.put("evidence", result.evidence());
        document.put("completedAt", result.completedAt());

        JSON.writeValue(temporary.toFile(), document);
        try {
            Files.move(temporary, file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
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
