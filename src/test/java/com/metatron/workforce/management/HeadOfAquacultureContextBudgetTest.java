package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import com.metatron.workforce.testing.LocalExecutionServers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Production failure on 79e4d9c: with the real BIOS inputs the HOA planning step failed with
 * "worker-cognition-request-context-budget-exceeded:chars=26556:limit=16000" because raw governance file text
 * reached the brain's context. CTO decision: keep the 16,000-char limit; read → digest → plan instead.
 *
 * <p>C1/C2: the nine inputs at their exact bios main sizes (≈64k chars) now plan to completion with every request
 * that reaches cognition ≤ 16,000 chars (instructions + context), ACTION_PLAN_v1 written with all seven §7 parts
 * and published as one unmerged PR under the HOA write prefixes. C4: every required input carries both
 * hoa-input-read and hoa-digest evidence. The digest replies are worst case (exactly the requested maximum).</p>
 */
class HeadOfAquacultureContextBudgetTest {
    private static final int LIMIT = 16_000;

    @TempDir Path temp;

    @Test
    void biosMainSizedInputsPlanToAnUnmergedPrWithEveryCognitionRequestWithinBudget() throws Exception {
        assumeTrue(LocalExecutionServers.toolAvailable("git"), "git not available on PATH");
        Map<String, String> inputs = HoaPlanningHarness.biosMainSizedInputs();
        assertTrue(inputs.values().stream().mapToInt(String::length).sum() > 26_000, "C1 scenario: inputs exceed 26k chars");
        assertTrue(inputs.values().stream().anyMatch(text -> text.length() > LIMIT),
                "COVERAGE.md alone exceeds one cognition request");

        RecordingHoaIntelligence intelligence = new RecordingHoaIntelligence();
        HoaPlanningHarness.Run run = HoaPlanningHarness.run(temp, inputs, intelligence);

        // C1 (red on main): the step completes instead of failing on the context budget.
        assertEquals(Set.of("hoa-action-plan-v1"), Set.copyOf(run.work().completedStepIds()),
                "blocker: " + run.work().blocker() + "\nhistory: " + run.history());
        assertFalse(String.join("\n", run.evidence()).contains("context-budget-exceeded"));

        // C2: no request that reached cognition exceeded the budget.
        assertFalse(intelligence.requests.isEmpty());
        for (WorkerIntelligenceService.Request request : intelligence.requests) {
            int chars = request.instructions().length() + request.context().length();
            assertTrue(chars <= LIMIT, "cognition request of " + chars + " chars: "
                    + request.instructions().lines().findFirst().orElse(""));
        }
        assertTrue(intelligence.digestRequests() >= 9, "one or more digest calls per required input");
        assertTrue(intelligence.selectionRequests() >= 1, "the plan was authored by cognition");
        for (Map.Entry<String, String> input : inputs.entrySet()) {
            String distinctive = input.getValue().substring(input.getValue().length() - 200);
            assertTrue(intelligence.selectionContexts.stream().noneMatch(context -> context.contains(distinctive)),
                    "raw text of " + input.getKey() + " never reaches the planning brain");
        }

        assertEquals(1, run.pullRequests());
        assertEquals(0, run.merges());
        assertFalse(run.publishedPaths().isEmpty());
        for (String published : run.publishedPaths()) {
            assertTrue(published.startsWith("DOMAINS/AQUACULTURE/ACTION_PLANS/")
                    || published.startsWith("DOMAINS/AQUACULTURE/REPORTS/"), published);
        }
        for (String part : AquacultureDomainPlanningCapability.SECTION_7_PARTS) {
            assertTrue(run.plan().contains("## " + part), "missing §7 part: " + part);
        }

        // C4: each required input has both read and digest evidence; digests are within 1,500 chars.
        String joined = String.join("\n", run.evidence());
        for (String path : inputs.keySet()) {
            assertEquals(1, run.evidence().stream().filter(("hoa-input-read:" + path)::equals).count(), joined);
            List<String> digests = run.evidence().stream().filter(e -> e.startsWith("hoa-digest:" + path + ":")).toList();
            assertEquals(1, digests.size(), "hoa-digest for " + path + "\n" + joined);
            int chars = Integer.parseInt(digests.getFirst().substring(digests.getFirst().lastIndexOf(':') + 1));
            assertTrue(chars > 0 && chars <= 1_500, digests.getFirst());
        }
        assertTrue(joined.contains("hoa-model-identity:"), joined);
        assertTrue(joined.contains("github-merge-performed:false"), joined);
    }

    /** Only the LLM boundary is scripted; every request that reaches it is recorded and size-checked. */
    static final class RecordingHoaIntelligence implements WorkerIntelligenceService {
        private static final ObjectMapper JSON = new ObjectMapper();
        private static final Pattern AVAILABLE = Pattern.compile("\"availableActions\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
        private static final Pattern MAX_CHARS = Pattern.compile("\"maxChars\"\\s*:\\s*(\\d+)");
        final List<WorkerIntelligenceService.Request> requests = new CopyOnWriteArrayList<>();
        final List<String> selectionContexts = new CopyOnWriteArrayList<>();
        private int digests;
        private int selections;
        private boolean written;

        int digestRequests() { return digests; }
        int selectionRequests() { return selections; }

        @Override
        public synchronized Response reason(Request request) {
            requests.add(request);
            int n = requests.size();
            List<String> evidence = List.of(
                    "worker-cognition-evidence;provider=scripted-free-tier;model=scripted-hoa-model;latency_ms=0");
            if (request.instructions().contains("condense")) {
                digests++;
                Matcher max = MAX_CHARS.matcher(request.context());
                assertTrue(max.find(), "digest request declares maxChars");
                int limit = Integer.parseInt(max.group(1));
                String digest = ("Digest " + n + ": giữ nguyên mục bắt buộc, cổng, envelope, luật, mã gap G-AQ, trạng thái. ")
                        .repeat(80).substring(0, limit);
                return new Response("scripted-" + n, json(Map.of("digest", digest)), evidence);
            }
            if (request.instructions().contains("reflection brain")) {
                return new Response("scripted-" + n, "{\"decision\":\"COMPLETE\",\"summary\":\"ACTION_PLAN_v1 proposed\"}", evidence);
            }
            if (!request.instructions().contains("action-selection brain")) {
                throw new AssertionError("unexpected cognition request: " + request.instructions());
            }
            selections++;
            selectionContexts.add(request.context());
            for (String path : HoaPlanningHarness.BIOS_MAIN_INPUT_SIZES.keySet()) {
                if (!request.context().contains(path)) {
                    throw new AssertionError("planning context must carry the digest of " + path);
                }
            }
            Set<String> available = available(request.context());
            if (!written && available.contains("workspace.file.write")) {
                written = true;
                StringBuilder plan = new StringBuilder("# ACTION_PLAN_v1 — BIOS Aquaculture (P1)\n\n");
                for (String part : AquacultureDomainPlanningCapability.SECTION_7_PARTS) {
                    plan.append("## ").append(part).append("\n\n- nội dung ").append(part).append("\n\n");
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("actionRef", "workspace.file.write");
                payload.put("inputs", Map.of("path", AquacultureDomainPlanningCapability.ACTION_PLAN_V1_PATH,
                        "content", plan.toString()));
                payload.put("rationale", "scripted HOA step");
                return new Response("scripted-" + n, json(payload), evidence);
            }
            throw new AssertionError("no scripted answer; available=" + available);
        }

        private static Set<String> available(String context) {
            Matcher matcher = AVAILABLE.matcher(context);
            if (!matcher.find()) throw new AssertionError("no availableActions");
            Set<String> out = new LinkedHashSet<>();
            for (String raw : matcher.group(1).split(",")) {
                String value = raw.replace("\"", "").trim();
                if (!value.isBlank()) out.add(value);
            }
            return out;
        }

        private static String json(Object value) {
            try {
                return JSON.writeValueAsString(value);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
