package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.knowledge.KnowledgeDocument;
import com.metatron.workforce.interaction.knowledge.KnowledgeQuery;
import com.metatron.workforce.interaction.knowledge.KnowledgeRetrievalService;
import com.metatron.workforce.interaction.tools.DefaultToolFabric;
import com.metatron.workforce.interaction.tools.ToolRequest;
import com.metatron.workforce.interaction.tools.ToolResult;
import com.metatron.workforce.interaction.tools.WebSearchToolAdapter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves Case information requirements against grounded retrieval/tool evidence before frontier reasoning.
 * Source selection is driven by semantically/planner-produced requirement metadata, not Human-text keyword intent.
 * Acquisition is prioritized by information value and reassessed after every material acquisition.
 */
public final class InformationRequirementAcquisitionService {
    private static final int MAX_CONTEXT_CHARS = 24_000;
    private static final String LEGACY_GENERIC_CURRENT_QUERY = "current external evidence";

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

        List<InformationRequirement> requirements = new ArrayList<>(intelligenceCase.informationRequirements());
        Set<String> caseEvidence = new LinkedHashSet<>(intelligenceCase.evidenceReferences());
        Set<String> processedThisTurn = new LinkedHashSet<>();
        StringBuilder groundedContext = new StringBuilder();
        StringBuilder groundedExternalEvidence = new StringBuilder();
        boolean externalEvidenceAcquired = false;
        int acquisitions = 0;
        int acquisitionBudget = budget(normalized.requestedDepth(), requirements.size());

        while (acquisitions < acquisitionBudget) {
            int next = selectNext(requirements, normalized, processedThisTurn);
            if (next < 0) break;

            InformationRequirement requirement = requirements.get(next);
            processedThisTurn.add(requirement.requirementId());
            AcquisitionAttempt attempt = acquireOne(requirement, normalized, requester, intelligenceCase.caseId());
            acquisitions++;

            if (attempt.satisfied()) {
                caseEvidence.addAll(attempt.evidenceReferences());
                requirements.set(next, requirement.withResolution(
                        InformationRequirementStatus.SATISFIED,
                        attempt.evidenceReferences()));
                appendBounded(groundedContext, attempt.groundedContext());
                if (attempt.externalResult() != null && attempt.externalResult().success()) {
                    externalEvidenceAcquired = true;
                    appendBounded(groundedExternalEvidence, attempt.groundedContext());
                }
                continue;
            }

            List<String> retainedEvidence = requiresFreshExternal(normalized, requirement)
                    ? List.of()
                    : requirement.evidenceReferences();
            if (attempt.unresolvable()) {
                requirements.set(next, requirement.withResolution(
                        InformationRequirementStatus.UNRESOLVABLE,
                        retainedEvidence));
            } else {
                requirements.set(next, requirement.withResolution(
                        InformationRequirementStatus.DEFERRED,
                        retainedEvidence));
            }
        }

        int satisfied = 0;
        int unresolved = 0;
        for (InformationRequirement requirement : requirements) {
            if (requirement.status() == InformationRequirementStatus.SATISFIED) {
                satisfied++;
                caseEvidence.addAll(requirement.evidenceReferences());
            } else {
                unresolved++;
            }
        }

        IntelligenceCase updated = intelligenceCase.withInformationAssessment(
                List.copyOf(requirements),
                List.copyOf(caseEvidence),
                IntelligenceCaseStatus.REASONING);

        return new AcquisitionResult(
                updated,
                trim(groundedContext.toString(), MAX_CONTEXT_CHARS),
                externalEvidenceAcquired,
                externalEvidenceAcquired ? renderGroundedFallback(groundedExternalEvidence.toString()) : "",
                satisfied,
                unresolved);
    }

    private AcquisitionAttempt acquireOne(InformationRequirement requirement,
                                          NormalizedRequest normalized,
                                          String requester,
                                          String caseId) {
        // Fresh/current reality cannot be satisfied by previously admitted institutional material alone.
        // When semantics say current external data is required, revalidate reality first and fail closed
        // if that current source cannot be acquired; stale context may remain historical evidence but it
        // cannot satisfy the present requirement.
        if (requiresFreshExternal(normalized, requirement)) {
            ToolResult external = acquireExternal(normalized, requirement, requester, caseId);
            if (external.success()) {
                StringBuilder context = new StringBuilder();
                appendTool(context, requirement, external);
                return new AcquisitionAttempt(true, false, external.evidenceReferences(), context.toString(), external);
            }
            return new AcquisitionAttempt(false, true, List.of(), "", external);
        }

        List<KnowledgeDocument> documents = retrieveKnowledge(normalized, requirement);
        if (!documents.isEmpty()) {
            List<String> refs = new ArrayList<>();
            StringBuilder context = new StringBuilder();
            for (KnowledgeDocument document : documents) {
                refs.addAll(document.evidenceReferences());
                appendKnowledge(context, requirement, document);
            }
            return new AcquisitionAttempt(true, false, refs, context.toString(), null);
        }

        if (shouldAcquireExternal(normalized, requirement)) {
            ToolResult external = acquireExternal(normalized, requirement, requester, caseId);
            if (external.success()) {
                StringBuilder context = new StringBuilder();
                appendTool(context, requirement, external);
                return new AcquisitionAttempt(true, false, external.evidenceReferences(), context.toString(), external);
            }
            return new AcquisitionAttempt(false, true, List.of(), "", external);
        }

        return new AcquisitionAttempt(false, false, List.of(), "", null);
    }

    private int selectNext(List<InformationRequirement> requirements,
                           NormalizedRequest normalized,
                           Set<String> processedThisTurn) {
        return java.util.stream.IntStream.range(0, requirements.size())
                .filter(index -> !processedThisTurn.contains(requirements.get(index).requirementId()))
                .filter(index -> acquirable(requirements.get(index), normalized))
                .boxed()
                .max(Comparator.comparingInt(index -> informationValue(requirements.get(index), normalized)))
                .orElse(-1);
    }

    private static boolean acquirable(InformationRequirement requirement, NormalizedRequest normalized) {
        if (requirement.status() == InformationRequirementStatus.MISSING
                || requirement.status() == InformationRequirementStatus.CONFLICTED) return true;
        return requirement.status() == InformationRequirementStatus.SATISFIED
                && requiresFreshExternal(normalized, requirement);
    }

    private static int informationValue(InformationRequirement requirement,
                                        NormalizedRequest normalized) {
        int score = 0;
        if (requirement.status() == InformationRequirementStatus.CONFLICTED) score += 50;
        score += impactScore(requirement.impactIfUnknown());
        score += freshnessScore(requirement.freshnessRequirement());
        score += qualityScore(requirement.qualityRequirement());
        score -= costPenalty(requirement.acquisitionCostHint());
        score -= latencyPenalty(requirement.latencyHint());
        if (normalized.freshExternalDataRequired() && shouldAcquireExternal(normalized, requirement)) score += 30;
        return score;
    }

    private static int impactScore(String value) {
        String v = normalized(value);
        if (v.contains("material") || v.contains("change") || v.contains("limit")) return 40;
        if (v.contains("risk") || v.contains("decision")) return 30;
        return 15;
    }

    private static int freshnessScore(String value) {
        String v = normalized(value);
        if (v.contains("current") || v.contains("real-time") || v.contains("realtime")) return 25;
        if (v.contains("recent")) return 15;
        return 5;
    }

    private static int qualityScore(String value) {
        String v = normalized(value);
        if (v.contains("source-attributed") || v.contains("validated") || v.contains("material conclusion")) return 20;
        return 10;
    }

    private static int costPenalty(String value) {
        String v = normalized(value);
        if (v.contains("lower-cost") || v.contains("bounded") || v.contains("low")) return 0;
        if (v.contains("high") || v.contains("expensive")) return 20;
        return 5;
    }

    private static int latencyPenalty(String value) {
        String v = normalized(value);
        if (v.contains("interactive")) return 0;
        if (v.contains("extended")) return 5;
        return 2;
    }

    private static int budget(IntelligenceDepth depth, int requirementCount) {
        if (requirementCount <= 0) return 0;
        return switch (depth) {
            case FAST -> Math.min(requirementCount, 1);
            case ANALYZE -> Math.min(requirementCount, 6);
            case DEEP -> requirementCount;
        };
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

    private ToolResult acquireExternal(NormalizedRequest normalized,
                                       InformationRequirement requirement,
                                       String requester,
                                       String caseId) {
        String requirementQuestion = requirement.question().trim();
        String query = requirementQuestion.isBlank()
                || requirementQuestion.equalsIgnoreCase(LEGACY_GENERIC_CURRENT_QUERY)
                ? normalized.objective().trim()
                : requirementQuestion;
        query = searchOptimizedExternalQuery(canonicalExternalQuery(query));
        if (query.isBlank()) {
            return ToolResult.failure(new ToolRequest(
                    "ir-web-" + caseId + "-" + requirement.requirementId() + "-" + System.nanoTime(),
                    requester, WebSearchToolAdapter.CAPABILITY, "internet:web-search", "search", "", List.of()),
                    "fresh_semantic_query_missing");
        }
        ToolRequest request = new ToolRequest(
                "ir-web-" + caseId + "-" + requirement.requirementId() + "-" + System.nanoTime(),
                requester,
                WebSearchToolAdapter.CAPABILITY,
                "internet:web-search",
                "search",
                query,
                List.of());
        try {
            return tools.execute(request);
        } catch (RuntimeException failure) {
            return ToolResult.failure(request, "information_acquisition_failed:" + failure.getClass().getSimpleName());
        }
    }

    static String canonicalExternalQuery(String value) {
        if (value == null || value.isBlank()) return "";
        return value.trim()
                .replaceAll("(?iu)xác\\s+định", " ")
                .replaceAll("(?iu)đương\\s+nhiệm", "current")
                .replaceAll("(?iu)phiên\\s+bản", "version")
                .replaceAll("(?iu)ổn\\s+định", "stable")
                .replaceAll("(?iu)mới\\s+nhất", "latest")
                .replaceAll("(?iu)hiện\\s+tại", "current")
                .replaceAll("(?iu)hôm\\s+nay", "today")
                .replaceAll("(?iu)tổng\\s+thống", "president")
                .replaceAll("(?iu)tỷ\\s+giá", "exchange rate")
                .replaceAll("(?iu)thời\\s+tiết", "weather")
                .replaceAll("(?iu)dữ\\s+liệu", "data")
                .replaceAll("(?iu)kiểm\\s+tra", "check")
                .replaceAll("(?iu)trả\\s+lời", "answer")
                .replaceAll("(?iu)cung\\s+cấp", "provide")
                .replaceAll("(?iu)thông\\s+tin", "information")
                .replaceAll("(?iu)(?<!\\p{L})về(?!\\p{L})", "about")
                .replaceAll("(?iu)chính\\s+thức", "official")
                .replaceAll("(?iu)nêu\\s+nguồn", "cite source")
                .replaceAll("(?iu)(?<!\\p{L})bằng\\s+cách(?!\\p{L})", "using")
                .replaceAll("(?iu)(?<!\\p{L})tra\\s+cứu(?!\\p{L})", "check")
                .replaceAll("(?iu)(?<!\\p{L})kèm\\s+theo(?!\\p{L})", "with")
                .replaceAll("(?iu)(?<!\\p{L})người\\s+dùng(?!\\p{L})", "user")
                .replaceAll("(?iu)(?<!\\p{L})của(?!\\p{L})", "of")
                .replaceAll("(?iu)(?<!\\p{L})từ(?!\\p{L})", "from")
                .replaceAll("(?iu)(?<!\\p{L})và(?!\\p{L})", "and")
                .replaceAll("(?iu)(?<!\\p{L})nguồn(?!\\p{L})", "source")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static String searchOptimizedExternalQuery(String value) {
        if (value == null || value.isBlank()) return "";
        String canonical = value.trim().replaceAll("\\s+", " ");
        String lower = canonical.toLowerCase(Locale.ROOT);
        if (!lower.matches(".*\\bversion\\b.*")) return canonical;
        if (!(lower.matches(".*\\bstable\\b.*") || lower.matches(".*\\blatest\\b.*")
                || lower.matches(".*\\bcurrent\\b.*"))) return canonical;

        String subject = canonical
                .replaceAll("(?iu)\\b(?:version|stable|latest|current|currently|today|now|release)\\b", " ")
                .replaceAll("(?iu)\\b(?:check|answer|please|cite|source|sources|data|information|external|fresh|user|users|provide|provides|providing|about|official|officially)\\b", " ")
                .replaceAll("(?iu)(?<![\\p{L}\\p{N}_])(?:xác\\s+định|dựa\\s+trên|của|từ|và|là|gì|nguồn|người\\s+dùng|cho|hãy|đang|rồi)(?![\\p{L}\\p{N}_])", " ")
                .replaceAll("(?iu)\\b(?:what|which|is|are|the|a|an|of|from|for|and|with|using|to)\\b", " ")
                .replaceAll("[()?!.,;]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (subject.isBlank()) return canonical;
        return subject + " latest stable version";
    }

    private static boolean requiresFreshExternal(NormalizedRequest normalized, InformationRequirement requirement) {
        if (!normalized.freshExternalDataRequired()) return false;
        String freshness = normalized(requirement.freshnessRequirement());
        return freshness.isBlank()
                || freshness.contains("current")
                || freshness.contains("real-time")
                || freshness.contains("realtime")
                || freshness.contains("today")
                || freshness.contains("now")
                || shouldAcquireExternal(normalized, requirement);
    }

    private static boolean shouldAcquireExternal(NormalizedRequest normalized, InformationRequirement requirement) {
        if (normalized.freshExternalDataRequired()) return true;
        return requirement.preferredSourceClasses().contains("web/external research")
                || requirement.preferredSourceClasses().contains("authorized external structured data");
    }

    private static void appendKnowledge(StringBuilder out, InformationRequirement requirement, KnowledgeDocument document) {
        out.append("\nINSTITUTIONAL RETRIEVAL EVIDENCE\n")
                .append("requirement_id=").append(requirement.requirementId()).append('\n')
                .append("source=").append(document.sourceId()).append('\n')
                .append("evidence_refs=").append(document.evidenceReferences()).append('\n')
                .append("title=").append(document.title()).append('\n')
                .append("content=\n").append(document.content()).append('\n');
    }

    private static void appendTool(StringBuilder out, InformationRequirement requirement, ToolResult result) {
        out.append("\nEXTERNAL / TOOL EVIDENCE\n")
                .append("requirement_id=").append(requirement.requirementId()).append('\n')
                .append("capability=").append(result.capability()).append('\n')
                .append("evidence_refs=").append(result.evidenceReferences()).append('\n')
                .append("content=\n").append(result.output()).append('\n');
    }

    private static void appendBounded(StringBuilder target, String value) {
        if (value == null || value.isBlank() || target.length() >= MAX_CONTEXT_CHARS) return;
        int remaining = MAX_CONTEXT_CHARS - target.length();
        target.append(value, 0, Math.min(value.length(), remaining));
    }

    private static String renderGroundedFallback(String groundedEvidence) {
        if (groundedEvidence == null || groundedEvidence.isBlank()) return "";
        return "Metatron acquired grounded external evidence before frontier reasoning. "
                + "Provider synthesis was unavailable or violated the evidence contract, so the requirement-scoped grounded evidence is returned directly:\n\n"
                + trim(groundedEvidence, MAX_CONTEXT_CHARS).trim();
    }

    private static String trim(String value, int maxChars) {
        if (value == null || value.isBlank()) return "";
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record AcquisitionAttempt(
            boolean satisfied,
            boolean unresolvable,
            List<String> evidenceReferences,
            String groundedContext,
            ToolResult externalResult) {
        private AcquisitionAttempt {
            evidenceReferences = List.copyOf(Objects.requireNonNull(evidenceReferences, "evidenceReferences"));
            Objects.requireNonNull(groundedContext, "groundedContext");
        }
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
