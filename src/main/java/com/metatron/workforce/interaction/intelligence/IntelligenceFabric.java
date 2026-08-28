package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import com.metatron.workforce.interaction.tools.DefaultToolFabric;
import com.metatron.workforce.interaction.tools.ToolRequest;
import com.metatron.workforce.interaction.tools.ToolResult;
import com.metatron.workforce.interaction.tools.WebSearchToolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Shared intelligence capacity boundary above concrete provider transport. */
public final class IntelligenceFabric {
    private static final Logger LOG = LoggerFactory.getLogger(IntelligenceFabric.class);

    private final IntelligencePlanner planner;
    private final IntelligenceEngine engine;
    private final IntelligenceSynthesizer synthesizer;
    private final IntelligenceGovernance governance;
    private final DefaultToolFabric toolFabric;

    public IntelligenceFabric(
            IntelligencePlanner planner,
            IntelligenceEngine engine,
            IntelligenceSynthesizer synthesizer,
            IntelligenceGovernance governance) {
        this(planner, engine, synthesizer, governance,
                new DefaultToolFabric(List.of(new WebSearchToolAdapter())));
    }

    public IntelligenceFabric(
            IntelligencePlanner planner,
            IntelligenceEngine engine,
            IntelligenceSynthesizer synthesizer,
            IntelligenceGovernance governance,
            DefaultToolFabric toolFabric) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.synthesizer = Objects.requireNonNull(synthesizer, "synthesizer");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.toolFabric = Objects.requireNonNull(toolFabric, "toolFabric");
    }

    public IntelligencePlan plan(IntelligenceRequest request) {
        return planner.plan(request);
    }

    public IntelligenceResult execute(IntelligenceRequest request) {
        Objects.requireNonNull(request, "request");
        WebEnrichment enrichment = enrichWithWebEvidence(request);
        IntelligenceRequest enrichedRequest = enrichment.request();
        IntelligencePlan plan = planner.plan(enrichedRequest);
        List<LlmResponse> responses = new ArrayList<>();
        List<RuntimeException> failures = new ArrayList<>();

        for (LlmProvider provider : plan.providers()) {
            try {
                LlmResponse response = Objects.requireNonNull(
                        engine.execute(provider, enrichedRequest),
                        "intelligence engine response");
                if (response.provider() != provider) {
                    throw new IllegalStateException("provider attribution mismatch for " + provider);
                }
                responses.add(response);

                // SINGLE mode only needs the first successful provider. In multi-provider
                // mode we continue so the synthesizer receives every successful response.
                if (plan.collaborationMode() == CollaborationMode.SINGLE) {
                    break;
                }
            } catch (RuntimeException failure) {
                RuntimeException wrapped = new IllegalStateException(
                        "intelligence provider failed: " + provider + ": " + failure.getMessage(),
                        failure);
                failures.add(wrapped);
                LOG.warn("intelligence_provider_failed request_id={} provider={} reason={}",
                        enrichedRequest.requestId(), provider, failure.getMessage());
            }
        }

        if (responses.isEmpty()) {
            if (enrichment.webEvidence() != null && enrichment.webEvidence().success()) {
                String fallback = renderWebEvidenceFallback(enrichment.webEvidence());
                LOG.warn("intelligence_all_providers_failed_web_evidence_preserved request_id={} providers={} evidence_count={}",
                        enrichedRequest.requestId(), plan.providers(), enrichment.webEvidence().evidenceReferences().size());
                return new IntelligenceResult(enrichedRequest.requestId(), fallback, List.of());
            }

            IllegalStateException failure = new IllegalStateException(
                    "all selected intelligence providers failed: " + plan.providers());
            failures.forEach(failure::addSuppressed);
            LOG.error("intelligence_all_providers_failed request_id={} providers={} failure_count={}",
                    enrichedRequest.requestId(), plan.providers(), failures.size());
            throw failure;
        }

        String text = plan.collaborationMode() == CollaborationMode.SINGLE
                ? responses.getFirst().text()
                : Objects.requireNonNull(synthesizer.synthesize(enrichedRequest, List.copyOf(responses)),
                        "synthesized intelligence result");

        if (plan.requiresReasoning()) {
            governance.validate(enrichedRequest, List.copyOf(responses), text);
        }

        return new IntelligenceResult(
                enrichedRequest.requestId(),
                text,
                responses.stream()
                        .map(response -> new IntelligenceResult.ProviderResult(response.provider(), response))
                        .toList());
    }

    private WebEnrichment enrichWithWebEvidence(IntelligenceRequest request) {
        if (!requiresWebResearch(request.objective())) return new WebEnrichment(request, null);

        // Intelligence may propagate authority context supplied by the caller, but it must
        // never manufacture authority merely because it selected an evidence-gathering tool.
        List<String> authority = request.authorityContext().isBlank()
                ? List.of()
                : List.of(request.authorityContext());

        ToolRequest toolRequest = new ToolRequest(
                "web-research-" + request.requestId(),
                request.requester(),
                WebSearchToolAdapter.CAPABILITY,
                "internet:web-search",
                "search",
                request.objective(),
                authority);
        ToolResult result = toolFabric.execute(toolRequest);
        if (!result.success()) {
            LOG.warn("web_research_unavailable request_id={} reason={}", request.requestId(), result.output());
            return new WebEnrichment(request, result);
        }

        List<String> evidence = new ArrayList<>(request.evidenceReferences());
        evidence.addAll(result.evidenceReferences());
        String context = request.context()
                + "\n\nWEB RESEARCH EVIDENCE (retrieved by Workforce before reasoning):\n"
                + result.output()
                + "\n\nUse this evidence for current/external claims. Cite or name the source when useful. "
                + "Do not claim a web lookup occurred unless this evidence block is present.\n";

        LOG.info("web_research_complete request_id={} result_count={}",
                request.requestId(), result.evidenceReferences().size());

        IntelligenceRequest enriched = new IntelligenceRequest(
                request.requestId(),
                request.requester(),
                request.mode(),
                request.collaborationMode(),
                request.objective(),
                context,
                evidence,
                request.requiredCapability(),
                request.consequence(),
                request.latencyBudget(),
                request.costBudget(),
                request.authorityContext(),
                request.requiredOutput(),
                request.requestedProviders(),
                request.maxProviders());
        return new WebEnrichment(enriched, result);
    }

    private static String renderWebEvidenceFallback(ToolResult result) {
        String output = result.output() == null ? "" : result.output().trim();
        if (output.isBlank()) {
            return "Không thể tạo bản tổng hợp AI, nhưng Workforce đã xác nhận có external evidence. Hãy thử lại.";
        }

        List<String> useful = output.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.equals("WEB SEARCH RESULTS"))
                .filter(line -> !line.equals("CURRENT EXTERNAL DATA"))
                .filter(line -> !line.startsWith("query="))
                .filter(line -> !line.startsWith("retrieved_at="))
                .limit(18)
                .toList();

        StringBuilder answer = new StringBuilder("Thông tin web mới nhất Workforce vừa truy xuất:\n");
        for (String line : useful) {
            if (line.startsWith("url=")) {
                answer.append("Nguồn: ").append(line.substring(4)).append('\n');
            } else if (line.startsWith("snippet=")) {
                answer.append(line.substring(8)).append('\n');
            } else if (line.matches("\\[[0-9]+].*")) {
                answer.append("\n").append(line).append('\n');
            } else {
                answer.append(line).append('\n');
            }
        }
        answer.append("\nDữ liệu trên được trả trực tiếp từ external evidence; không bịa kết quả khi lớp AI synthesis tạm không khả dụng.");
        return answer.toString().trim();
    }

    private static boolean requiresWebResearch(String objective) {
        String value = objective == null ? "" : objective.toLowerCase(Locale.ROOT).trim();
        if (value.isBlank()) return false;
        return containsAny(value,
                "latest", "current", "today", "now", "news", "weather", "price", "rate",
                "search", "internet", "online", "who is", "what is", "where is", "when is", "how much",
                "hôm nay", "hiện tại", "mới nhất", "tin tức", "thời tiết", "giá ", "tỷ giá", "tỉ giá",
                "là gì", "ai là", "ở đâu", "khi nào", "bao nhiêu", "tìm kiếm");
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }

    private record WebEnrichment(IntelligenceRequest request, ToolResult webEvidence) {}
}
