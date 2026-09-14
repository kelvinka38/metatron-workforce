package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionWorkspaceManagerTest {
    @TempDir Path temp;

    @Test void attemptsOwnDistinctMutableRootsAndIndependentRepositories(){
        Instant t=Instant.parse("2026-09-12T00:00:00Z");
        ExecutionAttemptService attempts=new ExecutionAttemptService();
        ExecutionAttempt a=attempts.begin("d-a","objective:x","step:a","worker","assignment:a","auth","runtime:a",1,Duration.ofHours(1),t);
        ExecutionAttempt b=attempts.begin("d-b","objective:x","step:b","worker","assignment:b","auth","runtime:b",1,Duration.ofHours(1),t);
        ExecutionWorkspaceManager manager=new ExecutionWorkspaceManager(temp,attempts,new InMemoryExecutionWorkspaceBindingStore());

        ExecutionWorkspaceBinding wa=manager.allocate(a.attemptId(),a.fencingToken(),t.plusSeconds(1));
        ExecutionWorkspaceBinding wb=manager.allocate(b.attemptId(),b.fencingToken(),t.plusSeconds(1));
        assertNotEquals(wa.workspaceId(),wb.workspaceId());
        assertNotEquals(wa.rootPath(),wb.rootPath());

        manager.registerRepository(a.attemptId(),a.fencingToken(),"kelvinka38/metatron-workforce","main","workforce","metatron/a",t.plusSeconds(2));
        manager.markMaterialized(a.attemptId(),a.fencingToken(),"workforce","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","1111111111111111111111111111111111111111",t.plusSeconds(3));
        manager.registerRepository(a.attemptId(),a.fencingToken(),"kelvinka38/bios","main","bios","metatron/a-bios",t.plusSeconds(4));
        ExecutionWorkspaceBinding multi=manager.markMaterialized(a.attemptId(),a.fencingToken(),"bios","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","2222222222222222222222222222222222222222",t.plusSeconds(5));
        assertEquals(2,multi.repositories().size());
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",multi.requireComponent("workforce").baseSha());
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",multi.requireComponent("bios").baseSha());
        assertNotEquals(manager.repositoryPath(multi,"workforce"),manager.repositoryPath(multi,"bios"));
    }

    @Test void staleAttemptOrWorkspaceVersionFailsClosed(){
        Instant t=Instant.parse("2026-09-12T00:00:00Z");
        ExecutionAttemptService attempts=new ExecutionAttemptService();
        ExecutionAttempt a=attempts.begin("d1","o","same","w","a","z","r",1,Duration.ofHours(1),t);
        ExecutionWorkspaceManager manager=new ExecutionWorkspaceManager(temp,attempts,new InMemoryExecutionWorkspaceBindingStore());
        ExecutionWorkspaceBinding binding=manager.allocate(a.attemptId(),a.fencingToken(),t);
        manager.registerRepository(a.attemptId(),a.fencingToken(),"kelvinka38/universal","main","universal","metatron/u",t.plusSeconds(1));
        assertThrows(IllegalStateException.class,()->manager.requireVersion(a.attemptId(),binding.stateVersion()));

        ExecutionAttempt replacement=attempts.begin("d2","o","same","w","a2","z","r2",2,Duration.ofHours(1),t.plusSeconds(2));
        assertTrue(replacement.fencingToken()>a.fencingToken());
        assertThrows(RuntimeException.class,()->manager.requireActive(a.attemptId(),a.fencingToken(),t.plusSeconds(3)));
    }

    @Test void reclaimOrphanedDisposesBindingWithNoBackingAttempt(){
        Instant t=Instant.parse("2026-09-12T00:00:00Z");
        ExecutionAttemptService attempts=new ExecutionAttemptService();
        InMemoryExecutionWorkspaceBindingStore store=new InMemoryExecutionWorkspaceBindingStore();
        ExecutionWorkspaceBinding orphan=new ExecutionWorkspaceBinding(
                "execution-workspace:orphan","orphan-attempt",1,"worker","objective:legacy","step",
                temp.resolve("orphan").toString(),1,ExecutionWorkspaceBinding.Status.MATERIALIZING,
                java.util.List.of(),t,t,null,null);
        store.save(java.util.Map.of("orphan-attempt",orphan));
        ExecutionWorkspaceManager manager=new ExecutionWorkspaceManager(temp,attempts,store);

        assertTrue(attempts.find("orphan-attempt").isEmpty());
        ExecutionWorkspaceBinding disposed=manager.reclaimOrphaned("orphan-attempt",1,t.plusSeconds(1));
        assertEquals(ExecutionWorkspaceBinding.Status.DISPOSED,disposed.status());
    }

    @Test void reclaimOrphanedRefusesWhenAttemptActuallyExists(){
        Instant t=Instant.parse("2026-09-12T00:00:00Z");
        ExecutionAttemptService attempts=new ExecutionAttemptService();
        ExecutionAttempt a=attempts.begin("d","o","s","w","a","z","r",1,Duration.ofHours(1),t);
        ExecutionWorkspaceManager manager=new ExecutionWorkspaceManager(temp,attempts,new InMemoryExecutionWorkspaceBindingStore());
        ExecutionWorkspaceBinding binding=manager.allocate(a.attemptId(),a.fencingToken(),t);
        assertThrows(IllegalStateException.class,()->manager.reclaimOrphaned(a.attemptId(),binding.stateVersion(),t.plusSeconds(1)));
    }
}

