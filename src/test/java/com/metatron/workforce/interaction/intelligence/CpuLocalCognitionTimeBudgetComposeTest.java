package com.metatron.workforce.interaction.intelligence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production 2026-09-24: with local qwen3:4b on the CPU-only host (~55 prompt tokens/s, ~11 output
 * tokens/s), every Worker cognition call hit the paid-API-era 150 s Ollama timeout, and the step (30 min)
 * and Objective (1 h) bounds were equally too short for a multi-cycle local run. The production compose
 * file must carry time budgets that fit local inference, each outer layer longer than the one it wraps.
 */
class CpuLocalCognitionTimeBudgetComposeTest {
    private static final String COMPOSE_PATH = "deploy/docker-compose.yml";
    private static final String NODE_PATH = "deploy/cognition-node/server.js";

    @Test
    void productionTimeBudgetsFitCpuLocalInferenceAndNestCorrectly() throws Exception {
        String compose = Files.readString(Path.of(COMPOSE_PATH));
        String node = Files.readString(Path.of(NODE_PATH));

        long nodeTotal = number(node, "METATRON_COGNITION_TOTAL_TIMEOUT_MS, (\\d+)");
        long ollama = number(node, "OLLAMA_TIMEOUT_MS, (\\d+)");
        long http = number(compose, "METATRON_COGNITION_HTTP_TIMEOUT_MS:-(\\d+)");
        long queueWait = number(compose, "METATRON_COGNITION_QUEUE_WAIT_MS:-(\\d+)");
        long step = number(compose, "METATRON_AUTONOMY_NODE_EXECUTION_TIMEOUT_MS:-(\\d+)");
        long objectiveSeconds = number(compose, "METATRON_AUTONOMY_MAX_DURATION_SECONDS:-(\\d+)");

        assertTrue(ollama >= 600_000, "one local call must fit a full content-generation budget: " + ollama);
        assertTrue(ollama < nodeTotal && nodeTotal < http, "Ollama < node total < Workforce HTTP timeout");
        assertTrue(queueWait >= nodeTotal, "a queued call must be able to wait out one in-flight local call");
        assertTrue(step >= 10 * http, "one step must fit many local cognition cycles: " + step);
        assertTrue(objectiveSeconds * 1000 >= 3 * step, "the Objective bound must fit several steps: " + objectiveSeconds);
    }

    private static long number(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        assertTrue(matcher.find(), "missing " + regex);
        return Long.parseLong(matcher.group(1));
    }
}
