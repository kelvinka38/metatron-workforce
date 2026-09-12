package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Regression for the production incident: clean/build/branch operations must never share mutable state. */
class ExecutionWorkspaceIsolationRegressionTest {
    @TempDir Path temp;

    @Test void sameRepositoryConcurrentBuildCleanAndBranchSwitchHaveZeroCrossAgentInterference() throws Exception {
        Instant t = Instant.parse("2026-09-12T00:00:00Z");
        ExecutionAttemptService attempts = new ExecutionAttemptService();
        ExecutionAttempt a = attempts.begin("dispatch-a", "objective:collision-regression", "step-a", "worker-a",
                "assignment-a", "auth", "runtime-a", 1, Duration.ofHours(1), t);
        ExecutionAttempt b = attempts.begin("dispatch-b", "objective:collision-regression", "step-b", "worker-b",
                "assignment-b", "auth", "runtime-b", 1, Duration.ofHours(1), t);
        ExecutionWorkspaceManager manager = new ExecutionWorkspaceManager(temp.resolve("executions"), attempts,
                new InMemoryExecutionWorkspaceBindingStore());

        Path repoA = prepareSameRepository(manager, a, "agent-a", t.plusSeconds(1));
        Path repoB = prepareSameRepository(manager, b, "agent-b", t.plusSeconds(1));
        assertNotEquals(repoA, repoB);
        assertNotEquals(repoA.resolve(".git").toRealPath(), repoB.resolve(".git").toRealPath());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> buildA = pool.submit(() -> concurrentBuild(repoA, ready, go));
            Future<?> buildB = pool.submit(() -> concurrentBuild(repoB, ready, go));
            ready.await();
            go.countDown();
            buildA.get();
            buildB.get();
        } finally {
            pool.shutdownNow();
        }

        Path classA = repoA.resolve("build/classes/Main.class");
        Path classB = repoB.resolve("build/classes/Main.class");
        assertTrue(Files.isRegularFile(classA), "A build artifact missing");
        assertTrue(Files.isRegularFile(classB), "B build artifact missing");
        byte[] bDigestBefore = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(classB));

        // This is the exact historical collision class: A clean must not delete B's build output.
        run(repoA, "git", "clean", "-fdx");
        assertFalse(Files.exists(repoA.resolve("build")), "A clean did not clean A");
        assertTrue(Files.isRegularFile(classB), "A clean deleted B build output");
        assertArrayEquals(bDigestBefore, java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(classB)));

        String aBranchBefore = run(repoA, "git", "branch", "--show-current").trim();
        assertEquals("agent-a", aBranchBefore);
        run(repoB, "git", "switch", "-c", "agent-b-next");
        assertEquals("agent-b-next", run(repoB, "git", "branch", "--show-current").trim());
        assertEquals("agent-a", run(repoA, "git", "branch", "--show-current").trim(),
                "B branch switch changed A HEAD/branch");

        assertNotEquals(run(repoA, "git", "rev-parse", "--git-dir").trim(), "");
        assertNotEquals(run(repoB, "git", "rev-parse", "--git-dir").trim(), "");
    }

    private static Path prepareSameRepository(ExecutionWorkspaceManager manager, ExecutionAttempt attempt,
                                              String branch, Instant at) throws Exception {
        ExecutionWorkspaceBinding binding = manager.allocate(attempt.attemptId(), attempt.fencingToken(), at);
        binding = manager.registerRepository(attempt.attemptId(), attempt.fencingToken(),
                "kelvinka38/metatron-workforce", "main", "workforce", "metatron/" + branch, at.plusMillis(1));
        Path repo = manager.repositoryPath(binding, "workforce");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/Main.java"),
                "public final class Main { public static int value() { return 42; } }\n", StandardCharsets.UTF_8);
        run(repo, "git", "init", "-q", "-b", "main");
        run(repo, "git", "config", "user.name", "Metatron Regression");
        run(repo, "git", "config", "user.email", "regression@metatron.local");
        run(repo, "git", "add", "src/Main.java");
        run(repo, "git", "commit", "-q", "-m", "same immutable fixture");
        run(repo, "git", "switch", "-c", branch);
        return repo;
    }

    private static void concurrentBuild(Path repo, CountDownLatch ready, CountDownLatch go) {
        try {
            ready.countDown();
            go.await();
            Files.createDirectories(repo.resolve("build/classes"));
            String javac = Path.of(System.getProperty("java.home"), "bin", "javac").toString();
            run(repo, javac, "-d", "build/classes", "src/Main.java");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String run(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) throw new IllegalStateException(String.join(" ", command) + " failed exit=" + exit + " output=" + output);
        return output;
    }
}
