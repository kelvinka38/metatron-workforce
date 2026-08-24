package com.metatron.workforce.evidence;

import com.metatron.workforce.workers.WorkerResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class EvidenceWriter {


    public Path write(
            String taskId,
            WorkerResult result
    ) throws IOException {


        Path directory =
                Path.of(
                    "runtime-evidence",
                    taskId
                );


        Files.createDirectories(directory);


        Path file =
                directory.resolve(
                    "execution.json"
                );


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


        Files.writeString(
                file,
                json
        );


        return file;
    }
}