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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Shared provider-neutral intelligence capacity boundary. */
public final class IntelligenceFabric {
    private static final Logger LOG = LoggerFactory.getLogger(IntelligenceFabric.class);

    private final IntelligencePlanner planner;
    private final IntelligenceEngine engine;
    private final IntelligenceSynthesizer synthesizer;
    private final IntelligenceGovernance governance;
    private final DefaultToolFabric toolFabric;
    private final ExternalEvidenceResponseGuard externalEvidenceGuard;
    private final MultiModelDeliberationCoordinator deliberationCoordinator;

    public IntelligenceFabric(IntelligencePlanner planner,
                              IntelligenceEngine engine,
                              IntelligenceSynthesizer synthesizer,
                              IntelligenceGovernance governance) {
        this(planner, engine, synthesizer, governance,
                new DefaultToolFabric(List.of(new WebSearchToolAdapter())), null);
    }

    public IntelligenceFabric(IntelligencePlanner planner,
                              IntelligenceEngine engine,
                              IntelligenceSynthesizer synthesizer,
                              IntelligenceGovernance governance,
                              DefaultToolFabric toolFabric) {
        this(planner, engine, synthesizer, governance, toolFabric, null);
    }

    public IntelligenceFabric(IntelligencePlanner planner,
                              IntelligenceEngine engine,
                              IntelligenceSynthesizer synthesizer,
                              IntelligenceGovernance governance,
                              DefaultToolFabric toolFabric,
                              MultiModelDeliberationCoordinator deliberationCoordinator) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.synthesizer = Objects.requireNonNull(synthesizer, "synthesizer");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.toolFabric = Objects.requireNonNull(toolFabric, "toolFabric");
        this.externalEvidenceGuard = new ExternalEvidenceResponseGuard();
        this.deliberationCoordinator = deliberationCoordinator;
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
        boolean hasInitialExternalEvidence = enrichment.webEvidence() != null && enrichment.webEvidence().success();
        if (request.freshExternalDataRequired() && !hasInitialExternalEvidence) {
            String reason = enrichment.webEvidence() == null
                    ? "external_evidence_not_attempted"
                    : enrichment.webEvidence().output();
            LOG.warn("current_external_evidence_unavailable request_id={} reason={}",
                    enrichedRequest.requestId(), reason);
            return new IntelligenceResult(
                    enrichedRequest.requestId(),
                    "METATRON CURRENT INFORMATION BLOCKED\n"
                            + "reason=CURRENT_EXTERNAL_EVIDENCE_UNAVAILABLE\n"
                            + "objective=" + enrichedRequest.objective(),
                    List.of(),
                    enrichedRequest.evidenceReferences());
        }

        for (LlmProvider provider : plan.providers()) {
            try {
                LlmResponse response = Objects.requireNonNull(
                        engine.execute(provider, enrichedRequest), "intelligence engine response");
                if (response.provider() != provider) {
                    throw new IllegalStateException("provider attribution mismatch for " + provider);
                }
                if (hasInitialExternalEvidence) externalEvidenceGuard.validate(response.text());
                responses.add(response);
                if (plan.collaborationMode() == CollaborationMode.SINGLE) break;
            } catch (RuntimeException failure) {
                RuntimeException wrapped = new IllegalStateException(
                        "intelligence provider failed: " + provider + ": " + failure.getMessage(), failure);
                failures.add(wrapped);
                LOG.warn("intelligence_provider_failed request_id={} provider={} reason={}",
                        enrichedRequest.requestId(), provider, failure.getMessage());
            }
        }

        if (responses.isEmpty()) {
            if (hasInitialExternalEvidence) {
                String fallback = renderWebEvidenceFallback(enrichment.webEvidence());
                LOG.warn("intelligence_provider_outputs_rejected_web_evidence_preserved request_id={} providers={} evidence_count={}",
                        enrichedRequest.requestId(), plan.providers(), enrichment.webEvidence().evidenceReferences().size());
                return new IntelligenceResult(enrichedRequest.requestId(), fallback, List.of(),
                        enrichment.webEvidence().evidenceReferences());
            }
            String reasons = failures.stream()
                    .map(IntelligenceFabric::compactFailure)
                    .filter(reason -> !reason.isBlank())
                    .distinct()
                    .limit(6)
                    .reduce((left, right) -> left + " | " + right)
                    .orElse("unavailable");
            IllegalStateException failure = new IllegalStateException(
                    "all selected intelligence providers failed: " + plan.providers()
                            + "; reasons=" + reasons);
            failures.forEach(failure::addSuppressed);
            LOG.error("intelligence_all_providers_failed request_id={} providers={} failure_count={}",
                    enrichedRequest.requestId(), plan.providers(), failures.size());
            throw failure;
        }

        List<LlmResponse> synthesisResponses = List.copyOf(responses);
        String deliberationPrelude = "";
        List<String> deliberationEvidence = List.of();
        if (plan.collaborationMode() != CollaborationMode.SINGLE
                && deliberationCoordinator != null
                && responses.size() >= 2) {
            MultiModelDeliberationCoordinator.DeliberationOutcome outcome =
                    deliberationCoordinator.deliberate(enrichedRequest, List.copyOf(responses));
            synthesisResponses = outcome.responsesForSynthesis();
            deliberationPrelude = deliberationCoordinator.renderPrelude(outcome);
            deliberationEvidence = outcome.addedEvidenceReferences();
        }

        String text = plan.collaborationMode() == CollaborationMode.SINGLE
                ? synthesisResponses.getFirst().text()
                : Objects.requireNonNull(synthesizer.synthesize(enrichedRequest, synthesisResponses),
                        "synthesized intelligence result");
        if (!deliberationPrelude.isBlank()) text = deliberationPrelude + "\n\n" + text;

        boolean hasAnyExternalEvidence = hasInitialExternalEvidence || !deliberationEvidence.isEmpty();
        if (hasAnyExternalEvidence) externalEvidenceGuard.validate(text);
        if (plan.requiresReasoning()) governance.validate(enrichedRequest, synthesisResponses, text);

        Set<String> resultEvidence = new LinkedHashSet<>(enrichedRequest.evidenceReferences());
        resultEvidence.addAll(deliberationEvidence);
        return new IntelligenceResult(
                enrichedRequest.requestId(), text,
                synthesisResponses.stream()
                        .map(response -> new IntelligenceResult.ProviderResult(response.provider(), response)).toList(),
                List.copyOf(resultEvidence));
    }

    /**
     * External research is triggered by the frontier-normalized semantic contract,
     * never by Java keyword matching over Human text.
     */
    private WebEnrichment enrichWithWebEvidence(IntelligenceRequest request) {
        if (!request.freshExternalDataRequired()) return new WebEnrichment(request, null);

        List<String> authority = request.authorityContext().isBlank()
                ? List.of() : List.of(request.authorityContext());
        ToolRequest toolRequest = new ToolRequest(
                "web-research-" + request.requestId(), request.requester(),
                WebSearchToolAdapter.CAPABILITY, "internet:web-search", "search",
                request.objective(), authority);
        ToolResult result = toolFabric.execute(toolRequest);
        if (!result.success()) {
            LOG.warn("web_research_unavailable request_id={} reason={}", request.requestId(), result.output());
            return new WebEnrichment(request, result);
        }

        List<String> evidence = new ArrayList<>(request.evidenceReferences());
        evidence.addAll(result.evidenceReferences());
        String context = request.context()
                + "\n\nWEB RESEARCH EVIDENCE (retrieved by Workforce before provider reasoning):\n"
                + result.output()
                + "\n\nEXTERNAL-EVIDENCE CONTRACT:\n"
                + "Workforce has already accessed external sources for this request. "
                + "Use supplied evidence. Do not deny that retrieval occurred. "
                + "If the evidence does not establish the requested fact, state exactly what is and is not established. "
                + "Never invent a value absent from evidence.\n";

        LOG.info("web_research_complete request_id={} result_count={} normalized_objective_length={}",
                request.requestId(), result.evidenceReferences().size(), request.objective().length());

        IntelligenceRequest enriched = new IntelligenceRequest(
                request.requestId(), request.requester(), request.mode(), request.collaborationMode(),
                request.objective(), context, evidence, request.requiredCapability(), request.consequence(),
                request.latencyBudget(), request.costBudget(), request.authorityContext(), request.requiredOutput(),
                request.requestedProviders(), request.maxProviders(), request.freshExternalDataRequired());
        return new WebEnrichment(enriched, result);
    }

    private static String compactFailure(RuntimeException failure) {
        String message = failure == null ? "" : String.valueOf(failure.getMessage());
        String clean = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > 500) clean = clean.substring(0, 500);
        return clean;
    }

    private static String renderWebEvidenceFallback(ToolResult result) {
        String output = result.output() == null ? "" : result.output().trim();
        if (output.isBlank()) {
            return "Workforce đã truy xuất external evidence nhưng payload không đủ dữ liệu để trả lời an toàn.";
        }
        List<String> useful = output.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.equals("WEB SEARCH RESULTS"))
                .filter(line -> !line.equals("CURRENT EXTERNAL DATA"))
                .filter(line -> !line.startsWith("query="))
                .filter(line -> !line.startsWith("retrieved_at="))
                .limit(36)
                .toList();

        StringBuilder answer = new StringBuilder("Workforce đã truy xuất web cho yêu cầu này. Evidence hiện có:\n");
        for (String line : useful) {
            if (line.startsWith("url=")) answer.append("Nguồn: ").append(line.substring(4)).append('\n');
            else if (line.startsWith("snippet=")) answer.append(line.substring(8)).append('\n');
            else if (line.startsWith("source_excerpt=")) answer.append(line.substring(15)).append('\n');
            else if (line.matches("\\[[0-9]+].*")) answer.append("\n").append(line).append('\n');
            else answer.append(line).append('\n');
        }
        answer.append("\nProvider synthesis không đạt evidence-consistency contract, nên Metatron trả evidence trực tiếp thay vì bịa hoặc phủ nhận khả năng truy xuất.");
        return answer.toString().trim();
    }

    private record WebEnrichment(IntelligenceRequest request, ToolResult webEvidence) {}
}
