package com.metatron.workforce.execution;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionAttemptServiceTest {
    @Test void expiredRuntimeAttemptIsAbandonedAndReplacementFencesStaleCommit(){
        ExecutionAttemptService service=new ExecutionAttemptService();
        Instant t0=Instant.parse("2026-08-31T05:00:00Z");
        ExecutionAttempt first=service.begin("dispatch:1","objective:1","step:1","worker:stable","assignment:1","authorization:1","runtime:A",1,Duration.ofSeconds(5),t0);
        service.heartbeat(first.attemptId(),first.fencingToken(),Duration.ofSeconds(5),t0.plusSeconds(1));
        service.checkpoint(first.attemptId(),first.fencingToken(),"checkpoint:A:1",t0.plusSeconds(2));
        assertEquals(1,service.reconcileExpired(t0.plusSeconds(7)).size());
        assertEquals(ExecutionAttempt.Status.ABANDONED,service.find(first.attemptId()).orElseThrow().status());

        ExecutionAttempt replacement=service.begin("dispatch:2","objective:1","step:1","worker:stable","assignment:2","authorization:1","runtime:B",2,Duration.ofSeconds(10),t0.plusSeconds(8));
        assertEquals("worker:stable",replacement.workerId());
        assertTrue(replacement.fencingToken()>first.fencingToken());
        assertThrows(IllegalStateException.class,()->service.succeed(first.attemptId(),first.fencingToken(),t0.plusSeconds(9)));
        assertEquals(ExecutionAttempt.Status.SUCCEEDED,service.succeed(replacement.attemptId(),replacement.fencingToken(),t0.plusSeconds(9)).status());
    }

    @Test void liveAttemptIsFencedImmediatelyWhenReplacementStarts(){
        ExecutionAttemptService service=new ExecutionAttemptService(); Instant t=Instant.parse("2026-08-31T05:00:00Z");
        ExecutionAttempt a=service.begin("d1","o","s","w","a1","z","r1",1,Duration.ofMinutes(1),t);
        ExecutionAttempt b=service.begin("d2","o","s","w","a2","z","r2",2,Duration.ofMinutes(1),t.plusSeconds(1));
        assertEquals(ExecutionAttempt.Status.FENCED,service.find(a.attemptId()).orElseThrow().status());
        assertThrows(IllegalStateException.class,()->service.checkpoint(a.attemptId(),a.fencingToken(),"late",t.plusSeconds(2)));
        assertEquals(ExecutionAttempt.Status.SUCCEEDED,service.succeed(b.attemptId(),b.fencingToken(),t.plusSeconds(2)).status());
    }
}
