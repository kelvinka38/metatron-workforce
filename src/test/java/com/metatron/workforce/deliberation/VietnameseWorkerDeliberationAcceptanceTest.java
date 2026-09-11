package com.metatron.workforce.deliberation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VietnameseWorkerDeliberationAcceptanceTest {
    @TempDir Path temp;

    @Test
    void vietnameseUnderspecifiedCreativeObjectiveIsNotOneShot() {
        WorkerDeliberationRuntime runtime = new WorkerDeliberationRuntime(
                temp.resolve("state.json"), new ObjectMapper().findAndRegisterModules());

        WorkerDeliberationRuntime.Directive directive = runtime.prepare(
                "worker-bat-ky", "Viết cho tao một bài nhạc", "");

        assertEquals(WorkerNextMove.CLARIFY, directive.state().nextMove());
        assertEquals("OBJECTIVE", directive.state().intent());
    }

    @Test
    void vietnameseSimpleRewriteStillActsDirectly() {
        WorkerDeliberationRuntime runtime = new WorkerDeliberationRuntime(
                temp.resolve("state2.json"), new ObjectMapper().findAndRegisterModules());

        WorkerDeliberationRuntime.Directive directive = runtime.prepare(
                "worker-bat-ky", "Viết lại câu này cho lịch sự hơn: gửi báo cáo ngay", "");

        assertEquals(WorkerNextMove.ACT, directive.state().nextMove());
    }
}
