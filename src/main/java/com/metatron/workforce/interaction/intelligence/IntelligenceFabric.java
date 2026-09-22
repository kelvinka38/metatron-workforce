package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import com.metatron.workforce.interaction.tools.DefaultToolFabric;
import com.metatron.workforce.interaction.tools.ToolRequest;
import com.metatron.workforce.interaction.tools.ToolResult;
import com.metatron.workforce.interaction.tools.WebSearchToolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    private final CognitiveArtifactStore artifactStore;
    private final CognitionNeedGate cognitionNeedGate;
    private final MetatronCognitionClient metatronCognitionClient;
    private final CognitionAdmissionPolicy cognitionAdmissionPolicy;
    private final InferenceConsumptionLedger inferenceLedger;

    public IntelligenceFabric(IntelligencePlanner planner,
                              IntelligenceEngine engine,
                              IntelligenceSynthesizer synthesizer,
                              IntelligenceGovernance governance) {
        this(planner, engine, synthesizer, governance,
                new DefaultToolFabric(List.of(new WebSearchToolAdapter())), null, null);
    }

    public IntelligenceFabric(IntelligencePlanner planner,
                              IntelligenceEngine engine,
                              IntelligenceSynthesizer synthesizer,
                              IntelligenceGovernance governance,
                              DefaultToolFabric toolFabric) {
        this(planner, engine, synthesizer, governance, toolFabric, null, null);
    }

    public IntelligenceFabric(IntelligencePlanner planner,
                              IntelligenceEngine engine,
                              IntelligenceSynthesizer synthesizer,
                              IntelligenceGovernance governance,
                              DefaultToolFabric toolFabric,
                              MultiModelDeliberationCoordinator deliberationCoordinator) {
        this(planner, engine, synthesizer, governance, toolFabric, deliberationCoordinator, null);
    }

    public IntelligenceFabric(IntelligencePlanner planner,
                              IntelligenceEngine engine,
                              IntelligenceSynthesizer synthesizer,
                              IntelligenceGovernance governance,
                              DefaultToolFabric toolFabric,
                              MultiModelDeliberationCoordinator deliberationCoordinator,
                              CognitiveArtifactStore artifactStore) {
        this(planner, engine, synthesizer, governance, toolFabric, deliberationCoordinator, artifactStore,
                null, new CognitionAdmissionPolicy(), new InMemoryInferenceConsumptionLedger());
    }

    public IntelligenceFabric(IntelligencePlanner planner,
                              IntelligenceEngine engine,
                              IntelligenceSynthesizer synthesizer,
                              IntelligenceGovernance governance,
                              DefaultToolFabric toolFabric,
                              MultiModelDeliberationCoordinator deliberationCoordinator,
                              CognitiveArtifactStore artifactStore,
                              MetatronCognitionClient metatronCognitionClient,
                              CognitionAdmissionPolicy cognitionAdmissionPolicy,
                              InferenceConsumptionLedger inferenceLedger) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.synthesizer = Objects.requireNonNull(synthesizer, "synthesizer");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.toolFabric = Objects.requireNonNull(toolFabric, "toolFabric");
        this.externalEvidenceGuard = new ExternalEvidenceResponseGuard();
        this.deliberationCoordinator = deliberationCoordinator;
        this.artifactStore = artifactStore;
        this.cognitionNeedGate = new CognitionNeedGate();
        this.metatronCognitionClient = metatronCognitionClient;
        this.cognitionAdmissionPolicy = Objects.requireNonNull(cognitionAdmissionPolicy, "cognitionAdmissionPolicy");
        this.inferenceLedger = Objects.requireNonNull(inferenceLedger, "inferenceLedger");
    }

    public IntelligencePlan plan(IntelligenceRequest request) {
        return planner.plan(withInstitutionalContext(request));
    }

    public IntelligenceResult execute(IntelligenceRequest request) {
        IntelligenceRequest contextual = withInstitutionalContext(Objects.requireNonNull(request, "request"));
        return executeContextual(contextual, scopedContinuationReason(contextual), defaultBudget(contextual));
    }

    /**
     * Executes one logical cognition request with an optional reason for the first provider call.
     * A non-null firstCallReason means an earlier frontier call already happened for this same
     * logical request (for example semantic normalization followed by novel evidence acquisition).
     */
    public IntelligenceResult execute(IntelligenceRequest request,
                                      EscalationReason firstCallReason,
                                      ProviderBudget providerBudget) {
        IntelligenceRequest contextual = withInstitutionalContext(Objects.requireNonNull(request, "request"));
        return executeContextual(contextual, firstCallReason, providerBudget);
    }

    private IntelligenceResult executeContextual(IntelligenceRequest request,
                                                 EscalationReason firstCallReason,
                                                 ProviderBudget providerBudget) {
        Objects.requireNonNull(providerBudget, "providerBudget");

        String artifactFingerprint = reusableFingerprint(request);
        Optional<CognitiveArtifact> reusable = reusableArtifact(request);
        CognitionNeedGate.Decision gate = cognitionNeedGate.evaluateRuntime(new CognitionNeedGate.RuntimeInput(
                false,
                false,
                reusable.isPresent(),
                false,
                false));
        if (gate.disposition() == CognitionNeedGate.Disposition.NOT_REQUIRED && reusable.isPresent()) {
            CognitiveArtifact artifact = reusable.get();
            LOG.info("cognition_gate_not_required request_id={} reason={} artifact_id={} fingerprint={}",
                    request.requestId(), gate.reason(), artifact.artifactId(), artifact.inputFingerprint());
            return new IntelligenceResult(
                    request.requestId(), artifact.result(), List.of(), artifact.evidenceRefs());
        }
        if (gate.disposition() == CognitionNeedGate.Disposition.BLOCKED) {
            return new IntelligenceResult(
                    request.requestId(),
                    "METATRON COGNITION BLOCKED\nreason=" + gate.reason() + "\nobjective=" + request.objective(),
                    List.of(), request.evidenceReferences());
        }

        if (request.originContext().originType() == IntelligenceOriginType.WORKER) {
            return executeMetatronOwned(request);
        }

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

        int providerAttempt = 0;
        for (LlmProvider provider : plan.providers()) {
            EscalationReason escalationReason;
            if (providerAttempt == 0) {
                escalationReason = firstCallReason;
            } else if (plan.collaborationMode() == CollaborationMode.SINGLE) {
                escalationReason = EscalationReason.PROVIDER_FAILURE;
            } else {
                escalationReason = EscalationReason.EXPLICIT_HUMAN_REQUEST;
            }
            providerAttempt++;
            try {
                cognitionAdmissionPolicy.requireAllowed(
                        enrichedRequest.originContext(), IntelligenceComputeOwner.EXTERNAL_PAID);
                LlmResponse response = Objects.requireNonNull(
                        engine.execute(provider, enrichedRequest, escalationReason, providerBudget),
                        "intelligence engine response");
                if (response.provider() != provider) {
                    throw new IllegalStateException("provider attribution mismatch for " + provider);
                }
                if (hasInitialExternalEvidence) externalEvidenceGuard.validate(response.text());
                inferenceLedger.record(InferenceConsumptionRecord.from(
                        enrichedRequest.originContext(), IntelligenceComputeOwner.EXTERNAL_PAID,
                        "external:" + provider.name(), response.model(),
                        Math.max(0L, response.usage().inputTokens()), Math.max(0L, response.usage().outputTokens()),
                        0L, "SUCCESS", response.providerRequestReference()));
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
                    deliberationCoordinator.deliberate(enrichedRequest, List.copyOf(responses), providerBudget);
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
        List<String> finalEvidence = List.copyOf(resultEvidence);

        if (artifactStore != null && reusableRequest(request)
                && plan.collaborationMode() == CollaborationMode.SINGLE
                && synthesisResponses.size() == 1) {
            LlmResponse source = synthesisResponses.getFirst();
            CognitiveArtifact artifact = new CognitiveArtifact(
                    "cognitive-artifact-" + UUID.randomUUID(),
                    caseRef(enrichedRequest.context()),
                    enrichedRequest.requiredCapability(),
                    artifactFingerprint,
                    source.provider(),
                    source.model(),
                    text,
                    List.of(),
                    finalEvidence,
                    List.of(),
                    Instant.now(),
                    null,
                    true);
            artifactStore.save(artifact);
            LOG.info("cognitive_artifact_saved request_id={} artifact_id={} fingerprint={}",
                    request.requestId(), artifact.artifactId(), artifact.inputFingerprint());
        }

        return new IntelligenceResult(
                enrichedRequest.requestId(), text,
                synthesisResponses.stream()
                        .map(response -> new IntelligenceResult.ProviderResult(response.provider(), response)).toList(),
                finalEvidence);
    }

    private IntelligenceResult executeMetatronOwned(IntelligenceRequest request) {
        cognitionAdmissionPolicy.requireAllowed(request.originContext(), IntelligenceComputeOwner.METATRON_OWNED);
        if (metatronCognitionClient == null) {
            throw new IllegalStateException("METATRON_OWNED_COGNITION_UNAVAILABLE");
        }

        WebEnrichment enrichment = enrichWithWebEvidence(request);
        IntelligenceRequest enrichedRequest = enrichment.request();
        boolean hasExternalEvidence = enrichment.webEvidence() != null && enrichment.webEvidence().success();
        if (request.freshExternalDataRequired() && !hasExternalEvidence) {
            String reason = enrichment.webEvidence() == null
                    ? "external_evidence_not_attempted"
                    : enrichment.webEvidence().output();
            return new IntelligenceResult(
                    request.requestId(),
                    "METATRON CURRENT INFORMATION BLOCKED\nreason=CURRENT_EXTERNAL_EVIDENCE_UNAVAILABLE\nobjective="
                            + request.objective() + "\ndetail=" + reason,
                    List.of(), enrichedRequest.evidenceReferences());
        }

        long started = System.nanoTime();
        try {
            MetatronCognitionClient.Response response = metatronCognitionClient.reason(
                    new MetatronCognitionClient.Request(
                            enrichedRequest.requestId(), enrichedRequest.requiredCapability(),
                            enrichedRequest.objective(), enrichedRequest.context(),
                            enrichedRequest.evidenceReferences(), enrichedRequest.originContext(),
                            enrichedRequest.requiredOutput(), enrichedRequest.outputBudget()));
            long latencyMillis = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            inferenceLedger.record(InferenceConsumptionRecord.from(
                    enrichedRequest.originContext(), IntelligenceComputeOwner.METATRON_OWNED,
                    response.endpointId(), response.modelIdentity(), response.inputTokens(), response.outputTokens(),
                    latencyMillis, "SUCCESS", response.requestReference()));
            if (hasExternalEvidence) externalEvidenceGuard.validate(response.text());
            List<String> internalEvidence = new ArrayList<>(enrichedRequest.evidenceReferences());
            internalEvidence.add("metatron-cognition-endpoint:" + response.endpointId());
            if (!response.modelIdentity().isBlank()) {
                internalEvidence.add("metatron-cognition-model:" + response.modelIdentity());
            }
            if (!response.requestReference().isBlank()) {
                internalEvidence.add("metatron-cognition-request:" + response.requestReference());
            }
            IntelligenceOriginContext origin = enrichedRequest.originContext();
            internalEvidence.add("worker-cognition-evidence"
                    + ";provider=" + evidenceValue(response.provider())
                    + ";model=" + evidenceValue(response.modelIdentity())
                    + ";latency_ms=" + response.latencyMillis()
                    + ";fallbackOccurred=" + response.fallbackOccurred()
                    + ";providerAttempts=[" + response.providerAttempts().stream()
                            .map(IntelligenceFabric::evidenceValue).reduce((left, right) -> left + "|" + right).orElse("") + "]"
                    + ";objective_id=" + evidenceValue(origin.objectiveId())
                    + ";assignment_id=" + evidenceValue(origin.assignmentId())
                    + ";worker_id=" + evidenceValue(origin.workerId()));
            return new IntelligenceResult(
                    enrichedRequest.requestId(), response.text(), List.of(), List.copyOf(internalEvidence));
        } catch (RuntimeException failure) {
            long latencyMillis = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            inferenceLedger.record(InferenceConsumptionRecord.from(
                    enrichedRequest.originContext(), IntelligenceComputeOwner.METATRON_OWNED,
                    "metatron-cognition-node", "", 0L, 0L, latencyMillis,
                    "FAILED:" + failure.getClass().getSimpleName(), ""));
            throw failure;
        }
    }

    private static String evidenceValue(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replace(';', '_').replace('\n', ' ').replace('\r', ' ').trim();
    }

    public InferenceConsumptionLedger inferenceLedger() { return inferenceLedger; }

    public Optional<CognitiveArtifact> reusableArtifact(IntelligenceRequest request) {
        Objects.requireNonNull(request, "request");
        if (artifactStore == null || !reusableRequest(request)) return Optional.empty();
        return artifactStore.findReusable(reusableFingerprint(request), Instant.now());
    }

    public static ProviderBudget defaultBudget(IntelligenceRequest request) {
        Objects.requireNonNull(request, "request");
        ProviderBudget budget = ProviderBudget.forDepth(depthFrom(request));
        if (request.collaborationMode() != CollaborationMode.SINGLE) {
            budget = budget.withExplicitMultiModelRequest(request.maxProviders());
        }
        return budget;
    }

    private static EscalationReason scopedContinuationReason(IntelligenceRequest request) {
        if (CognitiveRequestScope.logicalRequestRef().isBlank()) return null;
        if (request.collaborationMode() != CollaborationMode.SINGLE) {
            return EscalationReason.EXPLICIT_HUMAN_REQUEST;
        }
        String context = request.context() == null ? "" : request.context();
        if (context.contains("GROUNDED INFORMATION ACQUIRED BEFORE FRONTIER REASONING")
                || context.contains("WEB RESEARCH EVIDENCE")) {
            return EscalationReason.NOVEL_INFORMATION_ACQUIRED;
        }
        return EscalationReason.INSUFFICIENT_EVIDENCE;
    }

    private static IntelligenceDepth depthFrom(IntelligenceRequest request) {
        String latency = request.latencyBudget() == null ? "" : request.latencyBudget().toLowerCase(Locale.ROOT);
        String cost = request.costBudget() == null ? "" : request.costBudget().toLowerCase(Locale.ROOT);
        if (latency.contains("extended") || cost.contains("deep")) return IntelligenceDepth.DEEP;
        if (latency.contains("analysis") || cost.contains("expanded")) return IntelligenceDepth.ANALYZE;
        return IntelligenceDepth.FAST;
    }

    private static boolean reusableRequest(IntelligenceRequest request) {
        return request.collaborationMode() == CollaborationMode.SINGLE
                && !request.freshExternalDataRequired();
    }

    private static String reusableFingerprint(IntelligenceRequest request) {
        return CognitiveFingerprint.sha256(
                request.objective(),
                CognitiveFingerprint.contextFingerprint(request.context()),
                request.evidenceReferences(),
                request.requiredCapability(),
                request.requiredOutput());
    }

    private static IntelligenceRequest withInstitutionalContext(IntelligenceRequest request) {
        InstitutionalContextPackage packageContext = CognitiveRequestScope.institutionalContext();
        if (packageContext == null || request.context().contains("institutional_context_fingerprint=")) {
            return request;
        }
        String context = request.context()
                + "\n\nMETATRON INSTITUTIONAL CONTEXT PACKAGE\n"
                + "institutional_context_fingerprint=" + packageContext.fingerprint() + "\n"
                + "authority_chain=" + packageContext.authorityChain() + "\n"
                + "canonical_refs=" + packageContext.canonicalRefs() + "\n"
                + "plan_refs=" + packageContext.planRefs() + "\n"
                + "runtime_refs=" + packageContext.runtimeRefs() + "\n"
                + "conflicts=" + packageContext.conflicts() + "\n"
                + "context_note=References are context/provenance only; they do not grant authority or authorization.";
        return new IntelligenceRequest(
                request.requestId(), request.requester(), request.mode(), request.collaborationMode(),
                request.objective(), context, request.evidenceReferences(), request.requiredCapability(),
                request.consequence(), request.latencyBudget(), request.costBudget(), request.authorityContext(),
                request.requiredOutput(), request.requestedProviders(), request.maxProviders(),
                request.freshExternalDataRequired(), request.originContext(), request.outputBudget());
    }

    private static String caseRef(String context) {
        if (context == null || context.isBlank()) return "";
        for (String line : context.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("case_id=")) return trimmed.substring("case_id=".length()).trim();
        }
        return "";
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
                request.requestedProviders(), request.maxProviders(), request.freshExternalDataRequired(),
                request.originContext(), request.outputBudget());
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
