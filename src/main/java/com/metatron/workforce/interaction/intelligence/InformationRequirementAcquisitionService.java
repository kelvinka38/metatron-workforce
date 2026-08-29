package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.knowledge.KnowledgeDocument;
import com.metatron.workforce.interaction.knowledge.KnowledgeQuery;
import com.metatron.workforce.interaction.knowledge.KnowledgeRetrievalService;
import com.metatron.workforce.interaction.tools.DefaultToolFabric;
import com.metatron.workforce.interaction.tools.ToolRequest;
import com.metatron.workforce.interaction.tools.ToolResult;
import com.metatron.workforce.interaction.tools.WebSearchToolAdapter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves Case information requirements against grounded retrieval/tool evidence before frontier reasoning.
 * Source selection is driven by semantically/planner-produced requirement metadata, not Human-text keyword intent.
 */
public final class InformationRequirementAcquisitionService {
    private static final int MAX_CONTEXT_CHARS = 24_000;

    private final KnowledgeRetrievalService knowledge;
    private final DefaultToolFabric tools;

    public InformationRequirementAcquisitionService(KnowledgeRetrievalService knowledge, DefaultToolFabric tools) {
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    public AcquisitionResult acquire(IntelligenceCase intelligenceCase,
                                     NormalizedRequest normalized,
                                     String requester) {
        Objects.requireNonNull(intelligenceCase, "intelligenceCase");
        Objects.requireNonNull(normalized, "normalized");
        Objects.requireNonNull(requester, "requester");

        List<InformationRequirement> resolved = new ArrayList<>();
        Set<String> caseEvidence = new LinkedHashSet<>(intelligenceCase.evidenceReferences());
        StringBuilder groundedContext = new StringBuilder();
        ToolResult external = null;
        boolean externalAttempted = false;
        int satisfied = 0;

        for (InformationRequirement requirement : intelligenceCase.informationRequirements()) {
            if (requirement.status() == InformationRequirementStatus.SATISFIED) {
                resolved.add(requirement);
                caseEvidence.addAll(requirement.evidenceReferences());
                satisfied++;
                continue;
            }

            List<KnowledgeDocument> documents = retrieveKnowledge(normalized, requirement);
            if (!documents.isEmpty()) {
                List<String> refs = new ArrayList<>();
                for (KnowledgeDocument document : documents) {
                    refs.addAll(document.evidenceReferences());
                    caseEvidence.addAll(document.evidenceReferences());
                    appendKnowledge(groundedContext, requirement, document);
                }
                resolved.add(requirement.withResolution(InformationRequirementStatus.SATISFIED, refs));
                satisfied++;
                continue;
            }

            if (shouldAcquireExternal(normalized, requirement)) {
                if (!externalAttempted) {
                    externalAttempted = true;
                    external = acquireExternal(normalized, requester, intelligenceCase.caseId());
                }
                if (external != null && external.success()) {
                    caseEvidence.addAll(external.evidenceReferences());
                    appendTool(groundedContext, requirement, external);
                    resolved.add(requirement.withResolution(
                            InformationRequirementStatus.SATISFIED, external.evidenceReferences()));
                    satisfied++;
                    continue;
                }
            }

            resolved.add(requirement);
        }

        IntelligenceCase updated = intelligenceCase.withInformationAssessment(
                resolved,
                List.copyOf(caseEvidence),
                IntelligenceCaseStatus.REASONING);

        int unresolved = Math.max(0, resolved.size() - satisfied);
        boolean externalEvidenceAcquired = external != null && external.success();
        return new AcquisitionResult(
                updated,
                trim(groundedContext.toString(), MAX_CONTEXT_CHARS),
                externalEvidenceAcquired,
                externalEvidenceAcquired ? renderGroundedFallback(external) : "",
                satisfied,
                unresolved);
    }

    private List<KnowledgeDocument> retrieveKnowledge(NormalizedRequest normalized, InformationRequirement requirement) {
        String queryText = requirement.question() + "\nobjective=" + normalized.objective();
        String scope = normalized.target().isBlank() ? "institutional" : normalized.target();
        try {
            return knowledge.retrieve(new KnowledgeQuery(queryText, scope, List.of(), 3));
        } catch (RuntimeException failure) {
            return List.of();
        }
    }

    private ToolResult acquireExternal(NormalizedRequest normalized, String requester, String caseId) {
        ToolRequest request = new ToolRequest(
                "ir-web-" + caseId + "-" + System.nanoTime(),
                requester,
                WebSearchToolAdapter.CAPABILITY,
                "internet:web-search",
                "search",
                normalized.objective(),
                List.of());
        try {
            return tools.execute(request);
        } catch (RuntimeException failure) {
            return ToolResult.failure(request, "information_acquisition_failed:" + failure.getClass().getSimpleName());
        }
    }

    private static boolean shouldAcquireExternal(NormalizedRequest normalized, InformationRequirement requirement) {
        if (normalized.freshExternalDataRequired()) return true;
        return requirement.preferredSourceClasses().contains("web/external research")
                || requirement.preferredSourceClasses().contains("authorized external structured data");
    }

    private static void appendKnowledge(StringBuilder out, InformationRequirement requirement, KnowledgeDocument document) {
        if (out.length() >= MAX_CONTEXT_CHARS) return;
        out.append("\nINSTITUTIONAL RETRIEVAL EVIDENCE\n")
                .append("requirement_id=").append(requirement.requirementId()).append('\n')
                .append("source=").append(document.sourceId()).append('\n')
                .append("evidence_refs=").append(document.evidenceReferences()).append('\n')
                .append("title=").append(document.title()).append('\n')
                .append("content=\n").append(document.content()).append('\n');
    }

    private static void appendTool(StringBuilder out, InformationRequirement requirement, ToolResult result) {
        if (out.length() >= MAX_CONTEXT_CHARS) return;
        out.append("\nEXTERNAL / TOOL EVIDENCE\n")
                .append("requirement_id=").append(requirement.requirementId()).append('\n')
                .append("capability=").append(result.capability()).append('\n')
                .append("evidence_refs=").append(result.evidenceReferences()).append('\n')
                .append("content=\n").append(result.output()).append('\n');
    }

    private static String renderGroundedFallback(ToolResult result) {
        if (result == null || !result.success() || result.output().isBlank()) return "";
        return "Metatron acquired grounded external evidence before frontier reasoning. "
                + "Provider synthesis was unavailable or violated the evidence contract, so the grounded evidence is returned directly:\n\n"
                + result.output().trim();
    }

    private static String trim(String value, int maxChars) {
        if (value == null || value.isBlank()) return "";
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    public record AcquisitionResult(
            IntelligenceCase intelligenceCase,
            String groundedContext,
            boolean externalEvidenceAcquired,
            String groundedFallback,
            int satisfiedRequirements,
            int unresolvedRequirements) {
        public AcquisitionResult {
            Objects.requireNonNull(intelligenceCase, "intelligenceCase");
            Objects.requireNonNull(groundedContext, "groundedContext");
            Objects.requireNonNull(groundedFallback, "groundedFallback");
            if (satisfiedRequirements < 0 || unresolvedRequirements < 0) {
                throw new IllegalArgumentException("requirement counts must not be negative");
            }
        }
    }
}
