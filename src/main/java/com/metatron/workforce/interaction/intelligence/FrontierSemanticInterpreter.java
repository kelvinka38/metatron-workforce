package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.FrontierCallBudget;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Frontier semantic boundary. Under Cognitive Runtime the same first frontier call may also
 * produce the terminal answer when no new evidence/tool work is required.
 */
public final class FrontierSemanticInterpreter {
    private static final String SYSTEM = """
            You are Metatron's semantic interface and first cognitive pass.
            Understand the Human in their own language, including Vietnamese, English, mixed language, slang, shorthand, typos and colloquial phrasing.
            Normalize meaning first. Do not perform institutional work decomposition, capability binding, authorization or evidence fabrication.

            Cognitive Runtime rule: one frontier call by default. When the terminal product is an answer and no new institutional/external evidence, deterministic tool result, execution authorization, decision authority, or Human clarification is required, produce the complete final answer in direct_response in this SAME call. This applies to FAST, ANALYZE and DEEP. ANALYZE/DEEP may reason more deeply in the same call; depth does not mean provider count. Leave direct_response empty when another deterministic/evidence acquisition step must happen first.

            Return ONLY one JSON object with these fields:
            objective: concise normalized objective
            target: subject/target, or empty string
            constraints: array of explicit constraints
            requested_depth: FAST | ANALYZE | DEEP
            requested_output: desired output, or "direct natural-language answer"
            explicit_assumptions: array
            explicit_prohibitions: array
            temporal_context: explicit time scope, or empty string
            unresolved_semantic_ambiguity: empty string when materially clear; otherwise ONE concise clarification question in the Human's language
            interaction_outcome: ANSWER | DURABLE_WORK | INSTITUTIONAL_DECISION
            evidence_scope: NONE | INSTITUTIONAL | CURRENT_EXTERNAL
            mode: CASUAL | DISCUSSION | REASONING | DECISION | EXECUTION
            collaboration_mode: SINGLE | INDEPENDENT_SECOND_OPINION | LEAD_REVIEW | CONSENSUS | ADVERSARIAL_REVIEW
            analytical_protocols: array containing zero or more of AUDIT, COMPARE, ROOT_CAUSE, PERFORMANCE, FORECAST, INVESTMENT, INCIDENT, RISK, IMPROVEMENT, DECISION
            deterministic_capability: NONE | CURRENT_TIME | GATEWAY_AUDIT
            deterministic_computations: array of zero or more objects with fields label, operation, operands, unit. operation is one of SUM, AVERAGE, DIFFERENCE, PRODUCT, DIVIDE, PERCENT_OF, PERCENT_CHANGE. operands are canonical decimal strings.
            fresh_external_data_required: boolean
            explicitly_requested_provider: GOOGLE | ANTHROPIC | OPENAI | null
            execution_authorization: NONE | NOW | AFTER_HUMAN_APPROVAL
            case_continuity: CONTINUE | NEW
            direct_response: complete natural answer in the Human's language when interaction_outcome=ANSWER, evidence_scope=NONE, collaboration_mode=SINGLE, deterministic_capability=NONE, deterministic_computations=[], unresolved_semantic_ambiguity is empty, and no additional institutional/external evidence is required; otherwise empty string

            Rules:
            - Interpret meaning; do not emulate a keyword router.
            - interaction_outcome is canonical product routing: ANSWER for the answer in this interaction; DURABLE_WORK for delegated institutional work that must persist; INSTITUTIONAL_DECISION only when Metatron is asked to make/approve an institutional decision.
            - execution_authorization=NOW only when THIS message delegates execution now. AFTER_HUMAN_APPROVAL is NOT DURABLE_WORK now. Do not create, admit, enqueue or accept a Workforce Objective before that later approval. The current terminal product is the proposal/design/plan and must remain ANSWER/REASONING until later approval.
            - A later explicit approval may be DURABLE_WORK/EXECUTION with execution_authorization=NOW using active Case/conversation context.
            - evidence_scope=CURRENT_EXTERNAL whenever correctness depends on current external reality; INSTITUTIONAL for internal/connected institutional evidence; otherwise NONE.
            - CURRENT_EXTERNAL => fresh_external_data_required=true.
            - DURABLE_WORK=>EXECUTION; INSTITUTIONAL_DECISION=>DECISION; ANSWER=>CASUAL/DISCUSSION/REASONING.
            - ACTIVE INTELLIGENCE CASE is runtime coordination context only, never authority/evidence/truth.
            - case_continuity=CONTINUE only when the request continues/refines/challenges the same bounded problem. Otherwise NEW.
            - Do not guess through material Human ambiguity. If a Human choice is required, ask one concise clarification question in unresolved_semantic_ambiguity and direct_response rather than guessing.
            - Do not ask the Human for information Metatron can obtain from available systems/evidence. Clarification is for Human-only semantic choice.
            - Select analytical protocols by actual analytical need; protocols may compose.
            - FAST is ordinary conversation/simple help; ANALYZE is evidence-grounded analysis/comparison/investigation; DEEP is explicitly deep/forensic/persistent investigation.
            - EXECUTION is durable institutional work or consequential side effects. An ordinary information lookup whose requested outcome is the answer itself is ANSWER, not EXECUTION.
            - A delegated read-only institutional audit is DURABLE_WORK/EXECUTION when Metatron is asked to take ownership, independently obtain evidence, plan/coordinate work, verify criteria, and deliver the resulting work product. Do not downgrade such work to ANSWER/REASONING merely because its governed effects are read-only.
            - REASONING means analyze or answer within the interaction without accepting durable institutional ownership. Evidence-grounded reasoning may use AUDIT/COMPARE/etc. protocols, but it is not a substitute for EXECUTION when the Human has delegated an Objective to Workforce.
            - deterministic_capability=CURRENT_TIME only for explicit current date/time/day requests.
            - deterministic_capability=GATEWAY_AUDIT only for current Gateway read-only capability inspection handled deterministically.
            - deterministic_computations are declarations for exact arithmetic only when operands are explicitly supplied/unambiguous; never invent an operand.
            - PERCENT_CHANGE operands are [new_value, baseline_value]. PERCENT_OF operands are [numerator, denominator]. DIFFERENCE/DIVIDE preserve left-to-right order.
            - A model name in ordinary discussion is not an explicitly requested provider unless the Human asks that provider to reason/respond/review.
            - collaboration_mode must remain SINGLE unless the Human explicitly requests another model/provider review, consensus, independent second opinion or adversarial review. Depth alone never enables multi-model.
            - Never manufacture FACT, EVIDENCE, AUTHORITY, AUTHORIZATION, WORKER IDENTITY, EXECUTION EVIDENCE or INSTITUTIONAL KNOWLEDGE.
            """;

    private static final List<String> STRONG_FRESHNESS_PHRASES = List.of(
            " currently ", " latest ", " today ", " right now ", " at the moment ",
            " up-to-date ", " up to date ", " newest ", " as of now ", " fresh data ",
            " current data ", " latest data ", " current source ", " latest source ",
            " hien tai ", " hom nay ", " bay gio ", " ngay luc nay ", " luc nay ",
            " moi nhat ", " du lieu moi ", " du lieu hien tai ", " nguon hien tai ",
            " nguon moi nhat ", " cap nhat moi ", " cap nhat hien tai "
    );

    private static final List<String> EXPLICIT_CURRENT_TIME_PHRASES = List.of(
            " what time ", " current time ", " local time ", " time is it ",
            " current date ", " today's date ", " todays date ", " what date ",
            " what day ", " day of week ", " day-of-week ",
            " may gio ", " gio hien tai ", " gio bay gio ", " bay gio la may gio ",
            " ngay may ", " hom nay la ngay may ", " thu may ", " hom nay thu may "
    );

    private static final List<String> CONTEXT_DEPENDENCY_PHRASES = List.of(
            " what about ", " how about ", " same one ", " same thing ", " that one ",
            " that ", " those ", " them ", " it ", " this one ", " as above ", " previous one ",
            " con ", " con no ", " no ", " cai do ", " cai nay ", " nhu tren ",
            " van de do ", " vay thi ", " truong hop do ", " thu do "
    );

    private final LlmProviderRouter router;
    private final Function<LlmProvider, String> modelSelector;
    private final List<LlmProvider> providers;
    private final ObjectMapper mapper;

    public FrontierSemanticInterpreter(LlmProviderRouter router,
                                       Function<LlmProvider, String> modelSelector,
                                       List<LlmProvider> configuredProviders,
                                       ObjectMapper mapper) {
        this.router = Objects.requireNonNull(router, "router");
        this.modelSelector = Objects.requireNonNull(modelSelector, "modelSelector");
        Objects.requireNonNull(configuredProviders, "configuredProviders");
        this.providers = configuredProviders.stream().distinct().toList();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public NormalizedRequest interpret(String humanText, String conversationContext, String channel) {
        return interpret(humanText, conversationContext, channel, "", (IntelligenceCase) null,
                ProviderBudget.forDepth(IntelligenceDepth.ANALYZE));
    }

    public NormalizedRequest interpret(String humanText, String conversationContext, String channel,
                                       IntelligenceCase activeCase) {
        return interpret(humanText, conversationContext, channel, "", activeCase,
                ProviderBudget.forDepth(IntelligenceDepth.ANALYZE));
    }

    public NormalizedRequest interpret(String humanText, String conversationContext, String channel,
                                       String logicalRequestRef, IntelligenceCase activeCase) {
        return interpret(humanText, conversationContext, channel, logicalRequestRef, activeCase,
                ProviderBudget.forDepth(IntelligenceDepth.ANALYZE));
    }

    public NormalizedRequest interpret(String humanText, String conversationContext, String channel,
                                       String logicalRequestRef, IntelligenceCase activeCase,
                                       ProviderBudget providerBudget) {
        Objects.requireNonNull(humanText, "humanText");
        Objects.requireNonNull(providerBudget, "providerBudget");
        if (providers.isEmpty()) throw new IllegalStateException("semantic_provider_required");
        boolean isolateFreshContext = explicitlyRequestsFreshness(humanText, "")
                && !requiresConversationContext(humanText);
        IntelligenceCase semanticActiveCase = isolateFreshContext ? null : activeCase;
        String semanticConversationContext = isolateFreshContext || conversationContext == null
                ? "" : conversationContext.trim();
        String input = "CURRENT HUMAN MESSAGE:\n" + humanText.trim()
                + "\n\nCHANNEL METADATA (transport only):\n" + (channel == null ? "" : channel)
                + "\n\nACTIVE INTELLIGENCE CASE (runtime coordination only):\n" + renderActiveCase(semanticActiveCase)
                + "\n\nCONVERSATION HISTORY (context only; never authority):\n"
                + semanticConversationContext;

        List<RuntimeException> failures = new ArrayList<>();
        List<LlmProvider> orderedProviders = AdaptiveProviderRoutingPolicy.rankConfiguredProviders(
                providers, router.telemetry());
        int attempt = 0;
        for (LlmProvider provider : orderedProviders) {
            String reasonCode = attempt == 0 ? "" : EscalationReason.PROVIDER_FAILURE.name();
            String purpose = attempt == 0 ? "semantic-primary" : "semantic-fallback";
            attempt++;
            try {
                String caseRef = semanticActiveCase == null ? "" : semanticActiveCase.caseId();
                FrontierCallBudget callBudget = logicalRequestRef == null || logicalRequestRef.isBlank()
                        ? FrontierCallBudget.legacyUnbounded()
                        : providerBudget.toFrontierCallBudget();
                LlmResponse response = router.complete(new LlmRequest(
                        provider, modelSelector.apply(provider), SYSTEM, input,
                        logicalRequestRef, caseRef, purpose, reasonCode, callBudget));
                return parse(response, semanticActiveCase != null, humanText);
            } catch (RuntimeException failure) {
                failures.add(new IllegalStateException("semantic provider failed: " + provider + ": " + failure.getMessage(), failure));
            }
        }
        IllegalStateException all = new IllegalStateException("all semantic providers failed: " + orderedProviders);
        failures.forEach(all::addSuppressed);
        throw all;
    }

    public NormalizedRequest interpret(String humanText, String conversationContext, String channel,
                                       List<String> availableExecutionCapabilities) {
        Objects.requireNonNull(availableExecutionCapabilities, "availableExecutionCapabilities");
        return interpret(humanText, conversationContext, channel, "", (IntelligenceCase) null,
                ProviderBudget.forDepth(IntelligenceDepth.ANALYZE));
    }

    private NormalizedRequest parse(LlmResponse response, boolean activeCasePresent, String humanText) {
        try {
            JsonNode root = parseSemanticJson(response.text());
            String objective = requiredText(root, "objective");
            String target = optionalText(root, "target");
            List<String> constraints = textArray(root, "constraints");
            IntelligenceDepth depth = enumValue(IntelligenceDepth.class, requiredText(root, "requested_depth"));
            String requestedOutput = optionalText(root, "requested_output");
            if (requestedOutput.isBlank()) requestedOutput = "direct natural-language answer";
            List<String> assumptions = textArray(root, "explicit_assumptions");
            List<String> prohibitions = textArray(root, "explicit_prohibitions");
            String temporalContext = optionalText(root, "temporal_context");
            String ambiguity = optionalText(root, "unresolved_semantic_ambiguity");
            IntelligenceMode proposedMode = enumValue(IntelligenceMode.class, requiredText(root, "mode"));
            boolean proposedFresh = root.path("fresh_external_data_required").asBoolean(false);
            InteractionOutcome outcome = interactionOutcome(root, proposedMode);
            ExecutionAuthorization executionAuthorization = executionAuthorization(root, outcome);
            if (ApprovalDeferredExecutionGuard.requiresApprovalBeforeExecution(humanText)) {
                executionAuthorization = ExecutionAuthorization.AFTER_HUMAN_APPROVAL;
            }
            if (executionAuthorization == ExecutionAuthorization.AFTER_HUMAN_APPROVAL) {
                outcome = InteractionOutcome.ANSWER;
            } else if (executionAuthorization == ExecutionAuthorization.NOW
                    && outcome == InteractionOutcome.ANSWER) {
                // Semantic self-consistency guard: NOW means this message delegates execution now.
                // A provider response that simultaneously says ANSWER is contradictory; preserve
                // the explicit execution authorization as the stronger canonical routing signal.
                outcome = InteractionOutcome.DURABLE_WORK;
            } else if (outcome == InteractionOutcome.DURABLE_WORK
                    && executionAuthorization != ExecutionAuthorization.NOW) {
                outcome = InteractionOutcome.ANSWER;
            }
            EvidenceScope evidenceScope = evidenceScope(root, proposedFresh);
            boolean explicitFreshness = explicitlyRequestsFreshness(humanText, temporalContext);
            if (outcome == InteractionOutcome.ANSWER && explicitFreshness) evidenceScope = EvidenceScope.CURRENT_EXTERNAL;
            IntelligenceMode mode = canonicalMode(outcome, proposedMode);
            boolean fresh = evidenceScope == EvidenceScope.CURRENT_EXTERNAL
                    || (outcome == InteractionOutcome.DURABLE_WORK && explicitFreshness);
            if (outcome == InteractionOutcome.ANSWER && fresh
                    && (mode == IntelligenceMode.CASUAL || mode == IntelligenceMode.DISCUSSION)) {
                mode = IntelligenceMode.REASONING;
            }
            CollaborationMode collaboration = enumValue(CollaborationMode.class, requiredText(root, "collaboration_mode"));
            List<AnalyticalProtocolType> protocols = enumArray(root, "analytical_protocols", AnalyticalProtocolType.class);
            DeterministicCapability deterministicCapability = enumValue(DeterministicCapability.class, requiredText(root, "deterministic_capability"));
            if (deterministicCapability == DeterministicCapability.CURRENT_TIME
                    && !explicitlyRequestsCurrentTime(humanText)) deterministicCapability = DeterministicCapability.NONE;
            List<DeterministicComputationSpec> computations = computationArray(root, "deterministic_computations");
            LlmProvider requestedProvider = nullableProvider(root.get("explicitly_requested_provider"));
            String caseValue = optionalText(root, "case_continuity");
            CaseContinuity continuity = caseValue.isBlank()
                    ? (activeCasePresent ? CaseContinuity.CONTINUE : CaseContinuity.NEW)
                    : enumValue(CaseContinuity.class, caseValue);
            if (!activeCasePresent) continuity = CaseContinuity.NEW;
            String directResponse = optionalText(root, "direct_response");
            return new NormalizedRequest(objective, target, constraints, depth, requestedOutput, assumptions,
                    prohibitions, temporalContext, ambiguity, mode, collaboration, protocols, deterministicCapability,
                    computations, List.of(), fresh, requestedProvider, response.provider(), continuity, directResponse);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("invalid semantic normalization from " + response.provider(), failure);
        }
    }

    /**
     * Frontier providers occasionally serialize otherwise valid semantic JSON with a raw control
     * character inside a quoted string. Parse strictly first. Only that narrow JSON-formatting
     * defect is retried with Jackson's control-character tolerance; required fields, enums,
     * ambiguity, execution authorization and all downstream semantic guards remain unchanged.
     */
    private JsonNode parseSemanticJson(String responseText) throws Exception {
        String jsonText = unwrapJson(responseText);
        try {
            return mapper.readTree(jsonText);
        } catch (JsonParseException strictFailure) {
            String message = strictFailure.getOriginalMessage() == null ? "" : strictFailure.getOriginalMessage();
            if (!message.contains("CTRL-CHAR") && !message.toLowerCase(Locale.ROOT).contains("control character")) {
                throw strictFailure;
            }
            ObjectMapper tolerant = mapper.copy()
                    .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
            return tolerant.readTree(jsonText);
        }
    }

    private static boolean explicitlyRequestsFreshness(String humanText, String temporalContext) {
        String human = folded(humanText);
        String temporal = folded(temporalContext);
        if (temporal.equals("current") || temporal.equals("currently") || temporal.equals("latest")
                || temporal.equals("today") || temporal.equals("now") || temporal.equals("present")
                || temporal.equals("hien tai") || temporal.equals("hom nay") || temporal.equals("bay gio")
                || temporal.equals("ngay luc nay") || temporal.equals("moi nhat")) return true;
        String padded = " " + human.replaceAll("\\s+", " ").trim() + " ";
        for (String phrase : STRONG_FRESHNESS_PHRASES) if (padded.contains(phrase)) return true;
        return false;
    }

    private static boolean requiresConversationContext(String humanText) {
        String normalized = folded(humanText).replaceAll("[^a-z0-9]+", " ").replaceAll("\\s+", " ").trim();
        String padded = " " + normalized + " ";
        for (String phrase : CONTEXT_DEPENDENCY_PHRASES) if (padded.contains(phrase)) return true;
        return false;
    }

    private static boolean explicitlyRequestsCurrentTime(String humanText) {
        String normalized = folded(humanText).replaceAll("[^a-z0-9]+", " ").replaceAll("\\s+", " ").trim();
        String padded = " " + normalized + " ";
        for (String phrase : EXPLICIT_CURRENT_TIME_PHRASES) if (padded.contains(phrase)) return true;
        return false;
    }

    private static String folded(String value) {
        if (value == null || value.isBlank()) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
    }

    private static InteractionOutcome interactionOutcome(JsonNode root, IntelligenceMode proposedMode) {
        String value = optionalText(root, "interaction_outcome");
        if (!value.isBlank()) return enumValue(InteractionOutcome.class, value);
        return switch (proposedMode) {
            case EXECUTION -> InteractionOutcome.DURABLE_WORK;
            case DECISION -> InteractionOutcome.INSTITUTIONAL_DECISION;
            case CASUAL, DISCUSSION, REASONING -> InteractionOutcome.ANSWER;
        };
    }

    private static EvidenceScope evidenceScope(JsonNode root, boolean proposedFresh) {
        String value = optionalText(root, "evidence_scope");
        if (!value.isBlank()) return enumValue(EvidenceScope.class, value);
        return proposedFresh ? EvidenceScope.CURRENT_EXTERNAL : EvidenceScope.NONE;
    }

    private static IntelligenceMode canonicalMode(InteractionOutcome outcome, IntelligenceMode proposedMode) {
        return switch (outcome) {
            case DURABLE_WORK -> IntelligenceMode.EXECUTION;
            case INSTITUTIONAL_DECISION -> IntelligenceMode.DECISION;
            case ANSWER -> switch (proposedMode) {
                case CASUAL -> IntelligenceMode.CASUAL;
                case DISCUSSION -> IntelligenceMode.DISCUSSION;
                case REASONING, DECISION, EXECUTION -> IntelligenceMode.REASONING;
            };
        };
    }

    private enum InteractionOutcome { ANSWER, DURABLE_WORK, INSTITUTIONAL_DECISION }
    private enum EvidenceScope { NONE, INSTITUTIONAL, CURRENT_EXTERNAL }
    private enum ExecutionAuthorization { NONE, NOW, AFTER_HUMAN_APPROVAL }

    private static ExecutionAuthorization executionAuthorization(JsonNode root, InteractionOutcome outcome) {
        String value = optionalText(root, "execution_authorization");
        if (!value.isBlank()) return enumValue(ExecutionAuthorization.class, value);
        return outcome == InteractionOutcome.DURABLE_WORK ? ExecutionAuthorization.NOW : ExecutionAuthorization.NONE;
    }

    private static String renderActiveCase(IntelligenceCase activeCase) {
        if (activeCase == null || activeCase.status() == IntelligenceCaseStatus.RESOLVED) return "NONE";
        return "case_id=" + activeCase.caseId()
                + "\nstatus=" + activeCase.status()
                + "\nobjective=" + activeCase.objective()
                + "\nlatest_conclusion=" + activeCase.latestConclusion()
                + "\nunknowns=" + activeCase.unknowns();
    }

    private static List<DeterministicComputationSpec> computationArray(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (!node.isArray()) return List.of();
        List<DeterministicComputationSpec> values = new ArrayList<>();
        node.forEach(item -> {
            if (!item.isObject()) return;
            String label = optionalText(item, "label");
            String operation = optionalText(item, "operation");
            List<String> operands = textArray(item, "operands");
            String unit = optionalText(item, "unit");
            if (label.isBlank() || operation.isBlank() || operands.isEmpty()) return;
            values.add(new DeterministicComputationSpec(label,
                    enumValue(DeterministicComputationOperation.class, operation), operands, unit));
        });
        return List.copyOf(values);
    }

    private static String unwrapJson(String text) {
        String value = text.trim();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) value = value.substring(firstNewline + 1, lastFence).trim();
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalStateException("semantic response is not JSON");
        return value.substring(start, end + 1);
    }

    private static String requiredText(JsonNode root, String field) {
        String value = optionalText(root, field);
        if (value.isBlank()) throw new IllegalStateException("semantic field missing: " + field);
        return value;
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isTextual() ? node.asText().trim() : "";
    }

    private static List<String> textArray(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(item -> { if (item.isTextual() && !item.asText().isBlank()) values.add(item.asText().trim()); });
        return List.copyOf(values);
    }

    private static <E extends Enum<E>> List<E> enumArray(JsonNode root, String field, Class<E> type) {
        JsonNode node = root.path(field);
        if (!node.isArray()) return List.of();
        List<E> values = new ArrayList<>();
        node.forEach(item -> { if (item.isTextual() && !item.asText().isBlank()) values.add(enumValue(type, item.asText())); });
        return values.stream().distinct().toList();
    }

    private static LlmProvider nullableProvider(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) return null;
        return enumValue(LlmProvider.class, node.asText());
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    }
}
