package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.execution.ExecutionCapabilityRegistry;
import com.metatron.workforce.execution.ExecutionCommand;
import com.metatron.workforce.execution.ExecutionResult;
import com.metatron.workforce.execution.GatewayAuditCapability;
import com.metatron.workforce.interaction.llm.AnthropicLlmProviderClient;
import com.metatron.workforce.interaction.llm.GoogleLlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.OpenAiLlmProviderClient;
import com.metatron.workforce.interaction.tools.CurrentTimeToolAdapter;
import com.metatron.workforce.interaction.tools.DefaultToolFabric;
import com.metatron.workforce.interaction.tools.ToolRequest;
import com.metatron.workforce.interaction.tools.ToolResult;
import com.metatron.workforce.interaction.tools.WebSearchToolAdapter;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Channel-neutral Human intelligence boundary. */
public final class MetatronIntelligenceResponder {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MetatronIntelligenceResponder.class);
    private static final String SYSTEM_CONTEXT = """
            You are Metatron Workforce's intelligence layer.
            Answer the Human directly and naturally in the Human's language.
            The semantic objective supplied to you was normalized by a frontier model from the Human's original utterance.
            Use supplied conversation history and Case context to preserve continuity.
            Treat the inbound channel as transport metadata only.
            Do not claim that an action, audit, deployment, tool call, or external lookup happened unless Workforce supplied evidence of it.
            Claims, evidence, authority, authorization, execution and outcome are distinct.
            When evidence is insufficient or conflicting, preserve that uncertainty instead of fabricating completion.
            """;

    private final IntelligenceFabric fabric;
    private final FrontierSemanticInterpreter semanticInterpreter;
    private final IntelligenceCaseStore caseStore;
    private final String configuredProvider;
    private final int configuredProviderCount;
    private final ExecutionCapabilityRegistry capabilityRegistry;
    private final DefaultToolFabric toolFabric;

    public MetatronIntelligenceResponder(String openAiApiKey, String googleApiKey, String anthropicApiKey,
                                         String provider, String openAiModel, String googleModel,
                                         String anthropicModel, ObjectMapper objectMapper) {
        this(openAiApiKey, googleApiKey, anthropicApiKey, provider, openAiModel, googleModel, anthropicModel,
                objectMapper, "", "", new InMemoryIntelligenceCaseStore());
    }

    public MetatronIntelligenceResponder(String openAiApiKey, String googleApiKey, String anthropicApiKey,
                                         String provider, String openAiModel, String googleModel,
                                         String anthropicModel, ObjectMapper objectMapper,
                                         String gatewayAuditUrl, String gatewayAuditToken) {
        this(openAiApiKey, googleApiKey, anthropicApiKey, provider, openAiModel, googleModel, anthropicModel,
                objectMapper, gatewayAuditUrl, gatewayAuditToken, new InMemoryIntelligenceCaseStore());
    }

    public MetatronIntelligenceResponder(String openAiApiKey, String googleApiKey, String anthropicApiKey,
                                         String provider, String openAiModel, String googleModel,
                                         String anthropicModel, ObjectMapper objectMapper,
                                         String gatewayAuditUrl, String gatewayAuditToken,
                                         IntelligenceCaseStore caseStore) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        this.caseStore = Objects.requireNonNull(caseStore, "caseStore");
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_2).build();
        List<LlmProviderClient> clients = new ArrayList<>();
        List<LlmProvider> configuredProviders = new ArrayList<>();
        if (present(openAiApiKey)) { clients.add(new OpenAiLlmProviderClient(openAiApiKey, httpClient, objectMapper)); configuredProviders.add(LlmProvider.OPENAI); }
        if (present(googleApiKey)) { clients.add(new GoogleLlmProviderClient(googleApiKey, httpClient, objectMapper)); configuredProviders.add(LlmProvider.GOOGLE); }
        if (present(anthropicApiKey)) { clients.add(new AnthropicLlmProviderClient(anthropicApiKey, httpClient, objectMapper)); configuredProviders.add(LlmProvider.ANTHROPIC); }
        this.configuredProvider = normalizeProvider(provider);
        this.configuredProviderCount = configuredProviders.size();
        Function<LlmProvider, String> modelSelector = modelSelector(openAiModel, googleModel, anthropicModel);
        LlmProviderRouter router = new LlmProviderRouter(clients);
        this.semanticInterpreter = new FrontierSemanticInterpreter(router, modelSelector, configuredProviders, objectMapper);
        this.fabric = new IntelligenceFabric(
                new IntelligencePlanner(new ConfiguredProviderRoutingPolicy(configuredProviders)),
                new RouterBackedIntelligenceEngine(router, modelSelector),
                new EvidencePreservingIntelligenceSynthesizer(),
                new EvidenceBackedGovernance());
        if (present(gatewayAuditUrl)) this.capabilityRegistry = new ExecutionCapabilityRegistry(Map.of("gateway.audit.read", new GatewayAuditCapability(gatewayAuditUrl, gatewayAuditToken)));
        else this.capabilityRegistry = new ExecutionCapabilityRegistry(Map.of());
        this.toolFabric = new DefaultToolFabric(List.of(new CurrentTimeToolAdapter(), new WebSearchToolAdapter()));
    }

    public String respond(String humanId, String text, String externalMessageReference, String channel, String conversationContext) {
        return respond(humanId, text, externalMessageReference, channel,
                "conversation:" + channel + ":human:" + humanId, conversationContext);
    }

    public String respond(String humanId, String text, String externalMessageReference, String channel,
                          String conversationId, String conversationContext) {
        Objects.requireNonNull(humanId, "humanId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(externalMessageReference, "externalMessageReference");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(conversationId, "conversationId");
        long started = System.nanoTime();
        String route = "unknown";
        try {
            String command = text.trim().toLowerCase(Locale.ROOT);
            if ("/start".equals(command) || "/help".equals(command)) {
                route = "deterministic-start";
                return "Metatron Workforce online.\n\nGõ yêu cầu tự nhiên. Frontier models hiểu ngôn ngữ/slang/typo; Metatron xử lý context, evidence, logic, governance và capability phía sau.";
            }

            if (isLegacyGatewayAuditCompatibility(text)) {
                route = "gateway-audit-compatibility";
                return executeGatewayAudit(humanId, text, externalMessageReference, channel);
            }

            NormalizedRequest normalized = semanticInterpreter.interpret(text, conversationContext, channel);
            IntelligenceCase intelligenceCase = caseStore.openOrUpdate(conversationId, "human:" + humanId, normalized);
            route = "semantic-" + normalized.requestedDepth().name().toLowerCase(Locale.ROOT);

            if (normalized.deterministicCapability() == DeterministicCapability.CURRENT_TIME) {
                route = "deterministic-time-semantic";
                String answer = executeCurrentTime(humanId, externalMessageReference, channel);
                caseStore.save(intelligenceCase.withResult(answer, List.of("observation:" + channel + ":" + externalMessageReference)));
                return answer;
            }
            if (normalized.deterministicCapability() == DeterministicCapability.GATEWAY_AUDIT) {
                route = "gateway-audit-semantic";
                String answer = executeGatewayAudit(humanId, text, externalMessageReference, channel);
                caseStore.save(intelligenceCase.withResult(answer, List.of("observation:" + channel + ":" + externalMessageReference)));
                return answer;
            }

            if (normalized.mode() == IntelligenceMode.EXECUTION) {
                route = "execution-admission-blocked";
                caseStore.save(intelligenceCase.transition(IntelligenceCaseStatus.WAITING_ON_EXTERNAL_STATE));
                return "METATRON EXECUTION BLOCKED\nreason=EXECUTION_ADMISSION_REQUIRED\ncase_id=" + intelligenceCase.caseId() + "\nobjective=" + normalized.objective();
            }
            if (normalized.mode() == IntelligenceMode.DECISION) {
                route = "decision-authority-blocked";
                caseStore.save(intelligenceCase.transition(IntelligenceCaseStatus.WAITING_ON_EXTERNAL_STATE));
                return "METATRON DECISION BLOCKED\nreason=INSTITUTIONAL_AUTHORITY_REQUIRED\ncase_id=" + intelligenceCase.caseId() + "\nobjective=" + normalized.objective();
            }

            if (normalized.canReturnFastDirectly()) {
                route = "frontier-semantic-fast";
                caseStore.save(intelligenceCase.withResult(normalized.directResponse(), List.of()));
                return normalized.directResponse();
            }

            LlmProvider requested = normalized.explicitlyRequestedProvider();
            if (requested == null && !configuredProvider.isBlank()) requested = LlmProvider.valueOf(configuredProvider);
            List<LlmProvider> requestedProviders = requested == null ? List.of() : List.of(requested);
            CollaborationMode collaboration = normalized.collaborationMode();
            if (configuredProviderCount < 2 && collaboration != CollaborationMode.SINGLE) collaboration = CollaborationMode.SINGLE;
            int maxProviders = collaboration == CollaborationMode.SINGLE
                    ? (requested == null ? Math.max(1, configuredProviderCount) : 1)
                    : Math.min(3, configuredProviderCount);

            caseStore.save(intelligenceCase.transition(
                    normalized.freshExternalDataRequired() ? IntelligenceCaseStatus.ACQUISITION : IntelligenceCaseStatus.REASONING));
            String context = buildContext(channel, conversationContext, normalized, intelligenceCase);
            IntelligenceRequest request = new IntelligenceRequest(
                    "interaction-" + humanId + "-" + System.nanoTime(), "human:" + humanId,
                    normalized.mode(), collaboration, normalized.objective(), context,
                    List.of("observation:" + channel + ":" + externalMessageReference), "analysis",
                    consequence(normalized), latencyBudget(normalized.requestedDepth()), costBudget(normalized.requestedDepth()),
                    "", normalized.requestedOutput(), requestedProviders, maxProviders,
                    normalized.freshExternalDataRequired());
            route = "intelligence-" + normalized.requestedDepth().name().toLowerCase(Locale.ROOT)
                    + "-" + collaboration.name().toLowerCase(Locale.ROOT);
            IntelligenceResult result = fabric.execute(request);
            caseStore.save(intelligenceCase.withResult(result.text(), request.evidenceReferences()));
            return result.text();
        } finally {
            LOG.info("metatron_intelligence_latency channel={} route={} elapsed_ms={} text_length={}",
                    channel, route, (System.nanoTime() - started) / 1_000_000L, text.length());
        }
    }

    private static String buildContext(String channel, String conversationContext,
                                       NormalizedRequest normalized, IntelligenceCase intelligenceCase) {
        StringBuilder context = new StringBuilder(SYSTEM_CONTEXT)
                .append("\nInbound channel: ").append(channel).append(". Channel is transport only.")
                .append("\n\nINTELLIGENCE CASE (runtime coordination only; external institutional state remains referenced):")
                .append("\ncase_id=").append(intelligenceCase.caseId())
                .append("\ncase_status=").append(intelligenceCase.status())
                .append("\nprevious_conclusion=").append(intelligenceCase.latestConclusion())
                .append("\nprevious_unknowns=").append(intelligenceCase.unknowns())
                .append("\n\nNORMALIZED SEMANTIC REQUEST (interpretation, not authority/evidence):")
                .append("\nobjective=").append(normalized.objective())
                .append("\ntarget=").append(normalized.target())
                .append("\nconstraints=").append(normalized.constraints())
                .append("\nrequested_depth=").append(normalized.requestedDepth())
                .append("\nexplicit_assumptions=").append(normalized.explicitAssumptions())
                .append("\nexplicit_prohibitions=").append(normalized.explicitProhibitions())
                .append("\ntemporal_context=").append(normalized.temporalContext())
                .append("\nunresolved_semantic_ambiguity=").append(normalized.unresolvedSemanticAmbiguity());
        if (conversationContext != null && !conversationContext.isBlank()) {
            context.append("\n\nCONVERSATION HISTORY (chronological; context only, never authority):\n")
                    .append(conversationContext.trim());
        }
        return context.toString();
    }

    private String executeCurrentTime(String humanId, String externalMessageReference, String channel) {
        ToolRequest request = new ToolRequest("time-" + humanId + "-" + System.nanoTime(), "human:" + humanId,
                CurrentTimeToolAdapter.CAPABILITY, "runtime:workforce", "read", "current date and time",
                List.of(channel + ":" + externalMessageReference));
        ToolResult result = toolFabric.execute(request);
        if (!result.success()) throw new IllegalStateException("current_time_read_failed:" + result.output());
        Map<String, String> fields = parseKeyValueOutput(result.output());
        String day = switch (fields.getOrDefault("day_of_week", "")) {
            case "MONDAY" -> "Thứ Hai"; case "TUESDAY" -> "Thứ Ba"; case "WEDNESDAY" -> "Thứ Tư";
            case "THURSDAY" -> "Thứ Năm"; case "FRIDAY" -> "Thứ Sáu"; case "SATURDAY" -> "Thứ Bảy";
            case "SUNDAY" -> "Chủ Nhật"; default -> fields.getOrDefault("day_of_week", "không xác định");
        };
        return "Hôm nay là " + day + ", ngày " + fields.getOrDefault("current_date", "không xác định")
                + ". Giờ hiện tại: " + fields.getOrDefault("current_time", "không xác định") + " (giờ Việt Nam).";
    }

    private String executeGatewayAudit(String humanId, String text, String externalMessageReference, String channel) {
        String executionId = "audit-" + humanId + "-" + System.nanoTime();
        try {
            ExecutionResult result = capabilityRegistry.require("gateway.audit.read")
                    .execute(new ExecutionCommand(executionId, "gateway.audit.read", Instant.now()));
            return "METATRON GATEWAY AUDIT RESULT\nexecution_id=" + result.executionId() + "\ncapability=" + result.capability()
                    + "\nsuccess=" + result.success() + "\nsummary=" + result.summary() + "\nevidence=" + result.evidence()
                    + "\nsource=" + channel + ":" + externalMessageReference + "\nrequest=" + text.trim();
        } catch (RuntimeException failure) {
            return "METATRON GATEWAY AUDIT BLOCKED\nexecution_id=" + executionId + "\nreason=" + failure.getMessage();
        }
    }

    /** Existing deployed read-only shortcut retained only until capability selection is fully semantic. */
    private static boolean isLegacyGatewayAuditCompatibility(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        return value.contains("audit gateway") || value.contains("audit g4 gateway") || value.contains("audit g4");
    }

    private static String consequence(NormalizedRequest request) {
        if (request.requestedDepth() == IntelligenceDepth.DEEP) return "HIGH";
        if (request.requestedDepth() == IntelligenceDepth.ANALYZE || request.mode() == IntelligenceMode.REASONING) return "MEDIUM";
        return "LOW";
    }

    private static String latencyBudget(IntelligenceDepth depth) {
        return switch (depth) { case FAST -> "interactive-fast"; case ANALYZE -> "interactive-analysis"; case DEEP -> "extended-investigation"; };
    }

    private static String costBudget(IntelligenceDepth depth) {
        return switch (depth) { case FAST -> "standard"; case ANALYZE -> "expanded"; case DEEP -> "deep"; };
    }

    private static Map<String, String> parseKeyValueOutput(String output) {
        return output.lines().map(line -> line.split("=", 2)).filter(parts -> parts.length == 2)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(parts -> parts[0], parts -> parts[1], (a, b) -> b));
    }

    private static Function<LlmProvider, String> modelSelector(String openAiModel, String googleModel, String anthropicModel) {
        return provider -> switch (provider) {
            case OPENAI -> defaultModel(openAiModel, "gpt-4.1-mini");
            case GOOGLE -> defaultModel(googleModel, "gemini-3.7-flash");
            case ANTHROPIC -> defaultModel(anthropicModel, "claude-sonnet-4-20250514");
        };
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank() || "AUTO".equalsIgnoreCase(provider)) return "";
        return provider.trim().toUpperCase(Locale.ROOT);
    }
    private static String defaultModel(String configured, String fallback) { return configured == null || configured.isBlank() ? fallback : configured.trim(); }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
