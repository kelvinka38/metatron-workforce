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
        long http = number(compose, "METATRON_CPU_COGNITION_HTTP_TIMEOUT_MS:-(\\d+)");
        long queueWait = number(compose, "METATRON_CPU_COGNITION_QUEUE_WAIT_MS:-(\\d+)");
        long step = number(compose, "METATRON_AUTONOMY_NODE_EXECUTION_TIMEOUT_MS:-(\\d+)");
        long objectiveSeconds = number(compose, "METATRON_AUTONOMY_MAX_DURATION_SECONDS:-(\\d+)");

        assertTrue(ollama >= 600_000, "one local call must fit a full content-generation budget: " + ollama);
        assertTrue(ollama < nodeTotal && nodeTotal < http, "Ollama < node total < Workforce HTTP timeout");
        assertTrue(queueWait >= nodeTotal, "a queued call must be able to wait out one in-flight local call");
        assertTrue(step >= 10 * http, "one step must fit many local cognition cycles: " + step);
        assertTrue(objectiveSeconds * 1000 >= 3 * step, "the Objective bound must fit several steps: " + objectiveSeconds);

        // The host env file still carries the paid-API-era METATRON_COGNITION_HTTP_TIMEOUT_MS=240000 /
        // QUEUE_WAIT_MS=180000; compose must not read those names, or the new budgets are silently overridden.
        assertTrue(!compose.contains("${METATRON_COGNITION_HTTP_TIMEOUT_MS")
                        && !compose.contains("${METATRON_COGNITION_QUEUE_WAIT_MS"),
                "legacy env names must not feed the Workforce cognition budgets");
    }

    @Test
    void cognitionNodeIsDeployedByTheProductionPipelineOnlyWithLocalInferenceSettings() throws Exception {
        String compose = Files.readString(Path.of(COMPOSE_PATH));
        String deploy = Files.readString(Path.of("scripts/highway-tasks/workforce-deploy.sh"));
        int start = compose.indexOf("\n  cognition-node:");
        assertTrue(start > 0, "compose must define the cognition-node service");
        String service = compose.substring(start, compose.indexOf("\nvolumes:", start));
        assertTrue(service.contains("profiles: [\"production-cognition\"]"),
                "profile-gated so acceptance lanes never start or replace the production node");
        assertTrue(service.contains("container_name: metatron-cognition-node"), "Workforce reaches it by this name");
        assertTrue(service.contains("OLLAMA_MODEL: \"${METATRON_CPU_COGNITION_OLLAMA_MODEL:-qwen3:4b}\""));
        assertTrue(!service.contains("OPENAI_API_KEY") && !service.contains("ANTHROPIC_API_KEY"),
                "no credit-billed provider credential may reach the cognition node");
        assertTrue(deploy.contains("--profile production-cognition"), "production deploy must enable the profile");
        assertTrue(deploy.contains("build workforce workforce-sandbox cognition-node")
                        && deploy.contains("--force-recreate workforce-sandbox cognition-node workforce"),
                "production deploy must build and roll the cognition node with Workforce");
    }

    private static long number(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        assertTrue(matcher.find(), "missing " + regex);
        return Long.parseLong(matcher.group(1));
    }
}
