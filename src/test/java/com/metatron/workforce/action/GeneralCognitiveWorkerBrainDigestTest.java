package com.metatron.workforce.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C3: a governance document larger than one cognition request is digested map-reduce — bounded part digests, then
 * merged — into a final digest within the requested maximum, with every request ≤ 16,000 chars. The model is told
 * to preserve required items, gates, envelope, rules, gap codes and statuses and to add nothing.
 */
class GeneralCognitiveWorkerBrainDigestTest {
    private static final int LIMIT = GeneralCognitiveWorkerBrain.MAX_CONTEXT_PROMPT_CHARS;

    private static CognitiveWorkerRuntime.CognitiveContext context() {
        ExecutionWorkSpec work = new ExecutionWorkSpec("step-digest", "digest governance input", "kelvinka38/bios",
                "aquaculture.domain.planning", List.of(), ExecutionWorkSpec.Consequence.MUTATING);
        return new CognitiveWorkerRuntime.CognitiveContext("WORKER-HEAD-OF-AQUACULTURE", "assignment-digest",
                "authorization-digest", "objective-digest", work, "idempotency-digest", List.of(), List.of(), Map.of());
    }

    private static String document(int chars) {
        StringBuilder text = new StringBuilder("# COVERAGE\n\n");
        for (int i = 1; text.length() < chars; i++) {
            text.append("- G-AQ-").append(String.format("%04d", i)).append(": trạng thái OPEN; cổng P1; \"envelope\" \\ luật ")
                    .append(i).append('\n');
        }
        return text.substring(0, chars);
    }

    /** Worst-case model: always answers with exactly the requested maximum, never shorter. */
    private static final class MaximalDigestIntelligence implements WorkerIntelligenceService {
        private static final Pattern MAX = Pattern.compile("\"maxChars\"\\s*:\\s*(\\d+)");
        private static final Pattern MODE = Pattern.compile("\"mode\"\\s*:\\s*\"([a-z]+)\"");
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final List<String> modes = new CopyOnWriteArrayList<>();

        @Override
        public Response reason(Request request) {
            requests.add(request);
            assertTrue(request.instructions().contains("condense"), request.instructions());
            Matcher max = MAX.matcher(request.context());
            assertTrue(max.find());
            Matcher mode = MODE.matcher(request.context());
            assertTrue(mode.find());
            modes.add(mode.group(1));
            String digest = "mục bắt buộc; cổng; envelope; luật; G-AQ; trạng thái. ".repeat(200)
                    .substring(0, Integer.parseInt(max.group(1)));
            try {
                return new Response("digest-" + requests.size(), new ObjectMapper().writeValueAsString(Map.of("digest", digest)),
                        List.of("worker-cognition-evidence;provider=scripted;model=scripted-digest;latency_ms=0"));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    @Test
    void fortyThousandCharDocumentIsChunkedDigestedAndMergedWithinBudget() {
        MaximalDigestIntelligence intelligence = new MaximalDigestIntelligence();
        GeneralCognitiveWorkerBrain brain = new GeneralCognitiveWorkerBrain(intelligence, new ObjectMapper());
        String document = document(40_000);

        String digest = brain.digestDocument(context(), "KNOWLEDGE/DOMAINS/AQUACULTURE/v2/COVERAGE.md", document, 1_500);

        assertTrue(digest.length() <= 1_500, "digest " + digest.length());
        assertFalse(digest.isBlank());
        assertTrue(intelligence.modes.stream().filter("part"::equals).count() >= 3, intelligence.modes.toString());
        assertTrue(intelligence.modes.contains("merge"), intelligence.modes.toString());
        for (WorkerIntelligenceService.Request request : intelligence.requests) {
            int chars = request.instructions().length() + request.context().length();
            assertTrue(chars <= LIMIT, "digest request " + chars + " chars");
            assertTrue(request.instructions().contains("gap codes") && request.instructions().contains("Do not add"),
                    request.instructions());
        }
        assertTrue(brain.evidenceReferences().stream().anyMatch(ref -> ref.contains("model=scripted-digest")),
                "digest cognition leaves model-identity evidence like any other brain call");
    }

    @Test
    void smallDocumentIsDigestedInOneBoundedCall() {
        MaximalDigestIntelligence intelligence = new MaximalDigestIntelligence();
        GeneralCognitiveWorkerBrain brain = new GeneralCognitiveWorkerBrain(intelligence, new ObjectMapper());
        String digest = brain.digestDocument(context(), "DOMAINS/AQUACULTURE/DOMAIN_PACK/README.md", document(879), 1_500);
        assertEquals(1, intelligence.requests.size());
        assertEquals(List.of("part"), intelligence.modes);
        assertTrue(digest.length() <= 879, "a digest is never longer than its document");
    }

    @Test
    void overlongModelDigestIsShortenedOnceAndThenFailsExplicitly() {
        WorkerIntelligenceService verbose = request -> new WorkerIntelligenceService.Response("verbose",
                "{\"digest\":\"" + "x".repeat(3_000) + "\"}", List.of());
        GeneralCognitiveWorkerBrain brain = new GeneralCognitiveWorkerBrain(verbose, new ObjectMapper());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> brain.digestDocument(context(), "DOMAINS/AQUACULTURE/DECISIONS.md", document(6_195), 1_500));
        assertTrue(failure.getMessage().startsWith("worker-document-digest-over-budget:"), failure.getMessage());
    }
}
