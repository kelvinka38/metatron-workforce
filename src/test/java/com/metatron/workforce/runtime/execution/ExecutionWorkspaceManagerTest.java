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

    @Test void succeededStepCarriesPrimaryWorkspaceIntoNextAttemptButFailedStepDoesNot(){
        Instant t=Instant.parse("2026-09-12T00:00:00Z");
        ExecutionAttemptService attempts=new ExecutionAttemptService();
        ExecutionWorkspaceManager manager=new ExecutionWorkspaceManager(temp,attempts,new InMemoryExecutionWorkspaceBindingStore());

        ExecutionAttempt produce=attempts.begin("d-produce","objective:x","produce","worker","assignment:produce","auth","runtime:produce",1,Duration.ofHours(1),t);
        ExecutionWorkspaceBinding produced=manager.allocate(produce.attemptId(),produce.fencingToken(),t.plusSeconds(1));
        Path primary=Path.of(produced.rootPath()).resolve("repos").resolve("primary");
        try{
            java.nio.file.Files.createDirectories(primary.resolve(".git"));
            java.nio.file.Files.writeString(primary.resolve("package.json"), "{\"scripts\":{\"test\":\"echo ok\"}}");
            java.nio.file.Files.writeString(primary.resolve(".git").resolve("HEAD"), "ref: refs/heads/main\n");
            java.nio.file.Files.writeString(primary.resolve(".metatron-workspace"), "attempt=old\n");
        }catch(java.io.IOException e){throw new AssertionError(e);}
        attempts.succeed(produce.attemptId(),produce.fencingToken(),t.plusSeconds(2));

        ExecutionAttempt verify=attempts.begin("d-verify","objective:x","verify","worker","assignment:verify","auth","runtime:verify",1,Duration.ofHours(1),t.plusSeconds(3));
        ExecutionWorkspaceBinding verified=manager.allocate(verify.attemptId(),verify.fencingToken(),t.plusSeconds(4));
        Path carried=Path.of(verified.rootPath()).resolve("repos").resolve("primary");
        assertTrue(java.nio.file.Files.isRegularFile(carried.resolve("package.json")));
        assertTrue(java.nio.file.Files.isRegularFile(carried.resolve(".git").resolve("HEAD")));
        assertFalse(java.nio.file.Files.exists(carried.resolve(".metatron-workspace")),
                "per-attempt workspace identity must be regenerated, never carried forward");

        ExecutionAttempt failed=attempts.begin("d-fail","objective:y","produce","worker","assignment:failed","auth","runtime:failed",1,Duration.ofHours(1),t);
        ExecutionWorkspaceBinding failedBinding=manager.allocate(failed.attemptId(),failed.fencingToken(),t.plusSeconds(1));
        Path failedPrimary=Path.of(failedBinding.rootPath()).resolve("repos").resolve("primary");
        try{
            java.nio.file.Files.createDirectories(failedPrimary);
            java.nio.file.Files.writeString(failedPrimary.resolve("unsafe.txt"), "must-not-carry");
        }catch(java.io.IOException e){throw new AssertionError(e);}
        attempts.fail(failed.attemptId(),failed.fencingToken(),"synthetic failure",t.plusSeconds(2));

        ExecutionAttempt afterFailure=attempts.begin("d-after-fail","objective:y","verify","worker","assignment:after","auth","runtime:after",1,Duration.ofHours(1),t.plusSeconds(3));
        ExecutionWorkspaceBinding after=manager.allocate(afterFailure.attemptId(),afterFailure.fencingToken(),t.plusSeconds(4));
        assertFalse(java.nio.file.Files.exists(Path.of(after.rootPath()).resolve("repos").resolve("primary").resolve("unsafe.txt")));
    }

    /**
     * Production incident (2026-09-23): a real phased general-engineering Objective (PRODUCE/PREPARE/
     * VERIFY/DELIVER as separate governed ExecutionAttempts) reached DELIVER with all three prior phases
     * SUCCEEDED and a committed "primary" repository component, then permanently blocked with
     * "cannot carry forward committed repository component: primary" on every one of its 3 bounded-retry
     * attempts. allocate() always runs carryForwardSuccessfulPrimaryWorkspace immediately before
     * carryForwardCommittedComponents, and for the default "primary" component both copy into the exact
     * same repos/primary destination -- the second copy collided with files the first copy had just
     * placed, because only the first used REPLACE_EXISTING.
     */
    @Test void deliverAttemptAllocationSucceedsWhenAPriorStepAlreadyCommittedThePrimaryComponent(){
        Instant t=Instant.parse("2026-09-23T00:00:00Z");
        ExecutionAttemptService attempts=new ExecutionAttemptService();
        ExecutionWorkspaceManager manager=new ExecutionWorkspaceManager(temp,attempts,new InMemoryExecutionWorkspaceBindingStore());

        ExecutionAttempt verify=attempts.begin("d-verify","objective:control-center","verify","worker","assignment:verify","auth","runtime:verify",1,Duration.ofHours(1),t);
        ExecutionWorkspaceBinding verified=manager.allocate(verify.attemptId(),verify.fencingToken(),t.plusSeconds(1));
        Path primary=Path.of(verified.rootPath()).resolve("repos").resolve("primary");
        try{
            java.nio.file.Files.createDirectories(primary.resolve(".git"));
            java.nio.file.Files.writeString(primary.resolve("package.json"), "{\"scripts\":{\"test\":\"echo ok\"}}");
            java.nio.file.Files.writeString(primary.resolve(".git").resolve("HEAD"), "ref: refs/heads/main\n");
        }catch(java.io.IOException e){throw new AssertionError(e);}
        manager.registerRepository(verify.attemptId(),verify.fencingToken(),"kelvinka38/metatron-workforce-control-center","main","primary","metatron/attempt-verify",t.plusSeconds(2));
        manager.markMaterialized(verify.attemptId(),verify.fencingToken(),"primary",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","1111111111111111111111111111111111111111",t.plusSeconds(3));
        manager.markCommitted(verify.attemptId(),verify.fencingToken(),"primary","3333333333333333333333333333333333333333",t.plusSeconds(4));
        attempts.succeed(verify.attemptId(),verify.fencingToken(),t.plusSeconds(5));

        ExecutionAttempt deliver=attempts.begin("d-deliver","objective:control-center","deliver","worker","assignment:deliver","auth","runtime:deliver",1,Duration.ofHours(1),t.plusSeconds(6));
        ExecutionWorkspaceBinding delivered=manager.allocate(deliver.attemptId(),deliver.fencingToken(),t.plusSeconds(7));

        assertEquals(1,delivered.repositories().size());
        assertEquals(ExecutionRepositoryComponent.Status.COMMITTED,delivered.requireComponent("primary").status());
        Path carried=Path.of(delivered.rootPath()).resolve("repos").resolve("primary");
        assertTrue(java.nio.file.Files.isRegularFile(carried.resolve("package.json")),
                "committed source must still be present in the DELIVER attempt's workspace");
        assertTrue(java.nio.file.Files.isRegularFile(carried.resolve(".git").resolve("HEAD")));
    }

    /**
     * Production incident (2026-09-23), discovered immediately after the DELIVER-collision fix above
     * shipped and a real Node.js "Metatron Workforce Control Center" Objective's carry-forward reached a
     * real {@code node_modules/.bin} directory: every plain {@code npm install} creates such entries as
     * symlinks (e.g. {@code node_modules/.bin/esbuild -> ../esbuild/bin/esbuild}), and the carry-forward
     * walker rejected ANY symlink outright with "carry-forward source contains symlink: ...", making it
     * structurally impossible to ever carry forward a Node.js project's installed dependencies -- this
     * would have blocked every Node-based Objective, permanently, at this exact step.
     */
    @Test void relativeSymlinkInsideTheCarriedTreeIsResolvedAndCopiedInsteadOfRejected(){
        Instant t=Instant.parse("2026-09-23T00:00:00Z");
        ExecutionAttemptService attempts=new ExecutionAttemptService();
        ExecutionWorkspaceManager manager=new ExecutionWorkspaceManager(temp,attempts,new InMemoryExecutionWorkspaceBindingStore());

        ExecutionAttempt produce=attempts.begin("d-npm-produce","objective:npm-app","produce","worker","assignment:produce","auth","runtime:produce",1,Duration.ofHours(1),t);
        ExecutionWorkspaceBinding produced=manager.allocate(produce.attemptId(),produce.fencingToken(),t.plusSeconds(1));
        Path primary=Path.of(produced.rootPath()).resolve("repos").resolve("primary");
        try{
            Path esbuildPackageBin=primary.resolve("node_modules").resolve("esbuild").resolve("bin");
            java.nio.file.Files.createDirectories(esbuildPackageBin);
            java.nio.file.Files.writeString(esbuildPackageBin.resolve("esbuild"), "#!/bin/sh\necho esbuild\n");
            Path dotBin=primary.resolve("node_modules").resolve(".bin");
            java.nio.file.Files.createDirectories(dotBin);
            // Exactly npm's own convention: a relative symlink from node_modules/.bin into the package it wraps.
            java.nio.file.Files.createSymbolicLink(dotBin.resolve("esbuild"), Path.of("..", "esbuild", "bin", "esbuild"));
        }catch(java.io.IOException e){throw new AssertionError(e);}
        attempts.succeed(produce.attemptId(),produce.fencingToken(),t.plusSeconds(2));

        ExecutionAttempt verify=attempts.begin("d-npm-verify","objective:npm-app","verify","worker","assignment:verify","auth","runtime:verify",1,Duration.ofHours(1),t.plusSeconds(3));
        ExecutionWorkspaceBinding verified=manager.allocate(verify.attemptId(),verify.fencingToken(),t.plusSeconds(4));
        Path carriedBin=Path.of(verified.rootPath()).resolve("repos").resolve("primary").resolve("node_modules").resolve(".bin").resolve("esbuild");

        assertTrue(java.nio.file.Files.exists(carriedBin),"the symlinked binary's content must still be carried forward");
        assertFalse(java.nio.file.Files.isSymbolicLink(carriedBin),
                "the destination workspace must never contain a live symlink -- only dereferenced content");
        try{
            assertEquals("#!/bin/sh\necho esbuild\n", java.nio.file.Files.readString(carriedBin));
        }catch(java.io.IOException e){throw new AssertionError(e);}
    }

    @Test void symlinkResolvingOutsideTheCarriedTreeIsStillRejected(){
        Instant t=Instant.parse("2026-09-23T00:00:00Z");
        ExecutionAttemptService attempts=new ExecutionAttemptService();
        ExecutionWorkspaceManager manager=new ExecutionWorkspaceManager(temp,attempts,new InMemoryExecutionWorkspaceBindingStore());

        ExecutionAttempt produce=attempts.begin("d-escape-produce","objective:escape","produce","worker","assignment:produce","auth","runtime:produce",1,Duration.ofHours(1),t);
        ExecutionWorkspaceBinding produced=manager.allocate(produce.attemptId(),produce.fencingToken(),t.plusSeconds(1));
        Path primary=Path.of(produced.rootPath()).resolve("repos").resolve("primary");
        try{
            java.nio.file.Files.createDirectories(primary);
            Path outsideSecret=temp.resolve("outside-secret.txt");
            java.nio.file.Files.writeString(outsideSecret,"must-not-leak");
            java.nio.file.Files.createSymbolicLink(primary.resolve("escape"), outsideSecret);
        }catch(java.io.IOException e){throw new AssertionError(e);}
        attempts.succeed(produce.attemptId(),produce.fencingToken(),t.plusSeconds(2));

        ExecutionAttempt verify=attempts.begin("d-escape-verify","objective:escape","verify","worker","assignment:verify","auth","runtime:verify",1,Duration.ofHours(1),t.plusSeconds(3));
        assertThrows(SecurityException.class,
                ()->manager.allocate(verify.attemptId(),verify.fencingToken(),t.plusSeconds(4)),
                "a symlink resolving outside the carried source tree must still fail closed");
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

