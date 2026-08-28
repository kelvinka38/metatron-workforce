package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.execution.ExecutionCapabilityRegistry;
import com.metatron.workforce.execution.ExecutionCommand;
import com.metatron.workforce.execution.ExecutionResult;
import com.metatron.workforce.execution.GatewayAuditCapability;
import com.metatron.workforce.interaction.intelligence.CapacityAwareRoutingPolicy;
import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.EvidenceBackedGovernance;
import com.metatron.workforce.interaction.intelligence.IntelligenceFabric;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.IntelligencePlanner;
import com.metatron.workforce.interaction.intelligence.IntelligenceRequest;
import com.metatron.workforce.interaction.intelligence.ProviderCapacity;
import com.metatron.workforce.interaction.intelligence.RouterBackedIntelligenceEngine;
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

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Natural-language response path through the canonical Intelligence Fabric. */
public final class TelegramIntelligenceResponder {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TelegramIntelligenceResponder.class);
    private static final String SYSTEM_CONTEXT = """
            You are Metatron Workforce's intelligence layer.
            Answer the human directly and naturally.
            Preserve the user's language; Vietnamese is preferred when the user writes Vietnamese.
            Do not claim that an action, audit, deployment, tool call, or external lookup happened unless the Workforce actually supplied evidence of it.
            When a request requires tools or execution that are not connected to this conversation path, say so plainly instead of fabricating completion.
            Keep ordinary answers concise unless the user asks for depth.
            """;

    private final IntelligenceFabric fabric;
    private final String configuredProvider;
    private final ExecutionCapabilityRegistry capabilityRegistry;
    private final DefaultToolFabric toolFabric;

    public TelegramIntelligenceResponder(String openAiApiKey, String googleApiKey, String anthropicApiKey,
                                         String provider, String openAiModel, String googleModel,
                                         String anthropicModel, ObjectMapper objectMapper) {
        this(openAiApiKey, googleApiKey, anthropicApiKey, provider, openAiModel, googleModel, anthropicModel,
                objectMapper, "", "");
    }

    public TelegramIntelligenceResponder(String openAiApiKey, String googleApiKey, String anthropicApiKey,
                                         String provider, String openAiModel, String googleModel,
                                         String anthropicModel, ObjectMapper objectMapper,
                                         String gatewayAuditUrl, String gatewayAuditToken) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_2)
                .build();
        List<LlmProviderClient> clients = new ArrayList<>();
        List<ProviderCapacity> capacities = new ArrayList<>();
        if (present(openAiApiKey)) {
            clients.add(new OpenAiLlmProviderClient(openAiApiKey, httpClient, objectMapper));
            capacities.add(new ProviderCapacity(LlmProvider.OPENAI, true, 100, 1, 100_000, 500, 1));
        }
        if (present(googleApiKey)) {
            clients.add(new GoogleLlmProviderClient(googleApiKey, httpClient, objectMapper));
            capacities.add(new ProviderCapacity(LlmProvider.GOOGLE, true, 90, 1, 100_000, 500, 1));
        }
        if (present(anthropicApiKey)) {
            clients.add(new AnthropicLlmProviderClient(anthropicApiKey, httpClient, objectMapper));
            capacities.add(new ProviderCapacity(LlmProvider.ANTHROPIC, true, 80, 1, 100_000, 700, 1));
        }
        this.configuredProvider = normalizeProvider(provider);
        Function<LlmProvider, String> modelSelector = modelSelector(openAiModel, googleModel, anthropicModel);
        this.fabric = new IntelligenceFabric(
                new IntelligencePlanner(new CapacityAwareRoutingPolicy(capacities)),
                new RouterBackedIntelligenceEngine(new LlmProviderRouter(clients), modelSelector),
                (request, responses) -> responses.getFirst().text(),
                new EvidenceBackedGovernance());

        if (present(gatewayAuditUrl)) {
            this.capabilityRegistry = new ExecutionCapabilityRegistry(Map.of(
                    "gateway.audit.read", new GatewayAuditCapability(gatewayAuditUrl, gatewayAuditToken)));
        } else {
            this.capabilityRegistry = new ExecutionCapabilityRegistry(Map.of());
        }
        this.toolFabric = new DefaultToolFabric(List.of(new CurrentTimeToolAdapter()));
    }

    public String respond(String senderId, String text) {
        return respond(senderId, text, "telegram-message");
    }

    public String respond(String senderId, String text, String externalMessageReference) {
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(externalMessageReference, "externalMessageReference");
        long started = System.nanoTime();

        try {
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            if (isStartCommand(normalized)) {
                return "Metatron Workforce online.\n\nGõ yêu cầu tự nhiên, ví dụ:\n• audit g4 gateway\n• hôm nay thứ mấy?\n• hỏi thông tin mới nhất về ...\n• phân tích ...\n• Hey Gemini / Hey Claude / Hey OpenAI";
            }

            if (isGatewayAuditCommand(text)) return executeGatewayAudit(senderId, text, externalMessageReference);
            if (isCurrentTimeCommand(text)) return executeCurrentTime(senderId, externalMessageReference);

            LlmProvider requested = explicitProvider(text);
            if (requested == null && !configuredProvider.isBlank()) requested = LlmProvider.valueOf(configuredProvider);
            List<LlmProvider> requestedProviders = requested == null ? List.of() : List.of(requested);
            int maxProviders = requested == null ? 3 : 1;
            IntelligenceMode mode = resolveMode(text);
            String consequence = mode == IntelligenceMode.EXECUTION ? "HIGH" : mode == IntelligenceMode.DECISION ? "MEDIUM" : "LOW";

            // Do not pre-fetch the web here. IntelligenceFabric is the single authority for deciding
            // whether freshness/external evidence is required, so an ordinary interaction has zero
            // unnecessary Internet hops and a current-data interaction performs exactly one lookup.
            IntelligenceRequest request = new IntelligenceRequest(
                    "telegram-" + senderId + "-" + System.nanoTime(), "telegram:" + senderId,
                    mode, CollaborationMode.SINGLE, text,
                    SYSTEM_CONTEXT + "\nThe current inbound channel is Telegram.",
                    List.of("observation:telegram:" + externalMessageReference),
                    "analysis", consequence, "interactive-fast", "standard", "telegram-human",
                    "direct natural-language answer", requestedProviders, maxProviders);
            return fabric.execute(request).text();
        } finally {
            LOG.info("telegram_intelligence_latency elapsed_ms={} text_length={}",
                    (System.nanoTime() - started) / 1_000_000L, text.length());
        }
    }

    private static boolean isStartCommand(String normalized) { return "/start".equals(normalized) || "/help".equals(normalized); }

    private static LlmProvider explicitProvider(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        if (containsAny(value, "hey gemini", "hi gemini", "gemini:")) return LlmProvider.GOOGLE;
        if (containsAny(value, "hey claude", "hi claude", "claude:")) return LlmProvider.ANTHROPIC;
        if (containsAny(value, "hey openai", "hi openai", "openai:")) return LlmProvider.OPENAI;
        return null;
    }

    private String executeCurrentTime(String senderId, String externalMessageReference) {
        ToolRequest request = new ToolRequest("telegram-time-" + senderId + "-" + System.nanoTime(), "telegram-human",
                CurrentTimeToolAdapter.CAPABILITY, "runtime:workforce", "read", "current date and time",
                List.of("telegram:" + externalMessageReference));
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

    private static Map<String, String> parseKeyValueOutput(String output) {
        return output.lines().map(line -> line.split("=", 2)).filter(parts -> parts.length == 2)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(parts -> parts[0], parts -> parts[1]));
    }

    private String executeGatewayAudit(String senderId, String text, String externalMessageReference) {
        String executionId = "telegram-audit-" + senderId + "-" + System.nanoTime();
        try {
            ExecutionCommand command = new ExecutionCommand(executionId, "gateway.audit.read", Instant.now());
            ExecutionResult result = capabilityRegistry.require(command.action()).execute(command);
            return "METATRON GATEWAY AUDIT RESULT\nexecution_id=" + result.executionId() + "\ncapability="
                    + result.capability() + "\nsuccess=" + result.success() + "\nsummary=" + result.summary()
                    + "\nevidence=" + result.evidence() + "\nsource=telegram:" + externalMessageReference
                    + "\nrequest=" + text.trim();
        } catch (RuntimeException failure) {
            return "METATRON GATEWAY AUDIT BLOCKED\nexecution_id=" + executionId + "\nreason=" + failure.getMessage();
        }
    }

    private static boolean isGatewayAuditCommand(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        return containsAny(value, "audit g4 gateway", "audit gateway", "audit g4");
    }

    private static boolean isCurrentTimeCommand(String text) {
        String value = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return containsAny(value, "hôm nay là thứ mấy", "hôm nay thứ mấy", "hom nay la thu may", "hom nay thu may",
                "thứ mấy hôm nay", "thu may hom nay", "what day is today", "what day today", "today's date",
                "todays date", "what date is it", "what is today's date", "what time is it", "current time",
                "current date and time", "what's the date");
    }

    private static IntelligenceMode resolveMode(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        if (containsAny(value, "deploy", "execute", "run the fix", "ship it", "push to production", "fix it and deploy")) return IntelligenceMode.EXECUTION;
        if (containsAny(value, "decide", "approve", "authorize", "should we proceed", "make the decision")) return IntelligenceMode.DECISION;
        if (containsAny(value, "audit", "analyze", "analyse", "review", "diagnose", "compare", "investigate", "why", "root cause")) return IntelligenceMode.REASONING;
        return IntelligenceMode.DISCUSSION;
    }

    private static boolean containsAny(String value, String... terms) { for (String term : terms) if (value.contains(term)) return true; return false; }

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
