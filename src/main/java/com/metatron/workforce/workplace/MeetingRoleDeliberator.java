package com.metatron.workforce.workplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.AnthropicLlmProviderClient;
import com.metatron.workforce.interaction.llm.GoogleLlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import com.metatron.workforce.interaction.llm.OpenAiLlmProviderClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Role identity is institutional; provider identity is only inference substrate. */
public interface MeetingRoleDeliberator {
    Deliberation deliberate(String role, String purpose, String conversationContext);
    Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String conversationContext);

    record Deliberation(String text, String providerReference) {
        public Deliberation {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("deliberation text required");
            providerReference = providerReference == null ? "" : providerReference;
        }
    }

    static MeetingRoleDeliberator providerBacked(
            String openAiApiKey, String googleApiKey, String anthropicApiKey,
            String configuredProvider, String openAiModel, String googleModel, String anthropicModel,
            ObjectMapper json) {
        return new ProviderBacked(openAiApiKey, googleApiKey, anthropicApiKey, configuredProvider,
                openAiModel, googleModel, anthropicModel, json);
    }

    final class ProviderBacked implements MeetingRoleDeliberator {
        private final LlmProviderRouter router;
        private final LlmProvider provider;
        private final String model;

        ProviderBacked(String openAiApiKey, String googleApiKey, String anthropicApiKey,
                       String configuredProvider, String openAiModel, String googleModel, String anthropicModel,
                       ObjectMapper json) {
            Objects.requireNonNull(json, "json");
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                    .version(HttpClient.Version.HTTP_2).build();
            List<LlmProviderClient> clients = new ArrayList<>();
            if (present(openAiApiKey)) clients.add(new OpenAiLlmProviderClient(openAiApiKey, http, json));
            if (present(googleApiKey)) clients.add(new GoogleLlmProviderClient(googleApiKey, http, json));
            if (present(anthropicApiKey)) clients.add(new AnthropicLlmProviderClient(anthropicApiKey, http, json));
            if (clients.isEmpty()) throw new IllegalStateException("Meeting Room requires at least one configured frontier provider");
            this.router = new LlmProviderRouter(clients);
            this.provider = chooseProvider(clients, configuredProvider);
            this.model = switch (provider) {
                case OPENAI -> defaultModel(openAiModel, "gpt-4.1-mini");
                case GOOGLE -> defaultModel(googleModel, "gemini-3.7-flash");
                case ANTHROPIC -> defaultModel(anthropicModel, "claude-sonnet-4-20250514");
            };
        }

        @Override
        public Deliberation deliberate(String role, String purpose, String conversationContext) {
            String system = """
                    You are participating in an institutional Metatron Meeting Room as the named role below.
                    Role identity is institutional and is not the same as model/provider identity.
                    Analyze only from this role's professional accountability and constraints.
                    State: assessment, material risks, evidence/assumptions, recommendation, and disagreement you expect from other roles.
                    Do not claim authority to execute. Do not fabricate evidence, actions, approvals or consensus.
                    Keep the contribution concise but substantive.
                    ROLE: %s
                    """.formatted(role);
            String input = "MEETING PURPOSE:\n" + purpose + "\n\nCONVERSATION CONTEXT:\n" + safeContext(conversationContext);
            return complete(system, input);
        }

        @Override
        public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions,
                                       String conversationContext) {
            Objects.requireNonNull(contributions, "contributions");
            StringBuilder transcript = new StringBuilder();
            for (MeetingRecord.Contribution c : contributions) {
                transcript.append("\n--- ").append(c.role()).append(" ---\n").append(c.text()).append('\n');
            }
            String system = """
                    You are the neutral chair/synthesizer of an institutional Metatron Meeting Room.
                    Synthesize the attributed role contributions without inventing consensus.
                    Explicitly preserve meaningful disagreements and unresolved assumptions.
                    Produce: shared ground, disagreements, recommendation, risks, and proposed follow-up.
                    A meeting recommendation is NOT institutional authorization and MUST NOT be described as approval or execution.
                    """;
            String input = "MEETING PURPOSE:\n" + purpose
                    + "\n\nATTRIBUTED CONTRIBUTIONS:\n" + transcript
                    + "\n\nCONVERSATION CONTEXT:\n" + safeContext(conversationContext);
            return complete(system, input);
        }

        private Deliberation complete(String system, String input) {
            LlmResponse response = router.complete(new LlmRequest(provider, model, system, input));
            return new Deliberation(response.text(), "provider:" + response.provider().name().toLowerCase(Locale.ROOT)
                    + ":model:" + response.model() + ":request:" + String.valueOf(response.providerRequestReference()));
        }

        private static LlmProvider chooseProvider(List<LlmProviderClient> clients, String configured) {
            if (configured != null && !configured.isBlank() && !"AUTO".equalsIgnoreCase(configured)) {
                LlmProvider requested = LlmProvider.valueOf(configured.trim().toUpperCase(Locale.ROOT));
                if (clients.stream().anyMatch(c -> c.provider() == requested)) return requested;
                throw new IllegalStateException("configured Meeting provider is unavailable: " + requested);
            }
            return clients.get(0).provider();
        }

        private static String safeContext(String value) {
            if (value == null || value.isBlank()) return "(none)";
            String trimmed = value.trim();
            return trimmed.length() <= 12000 ? trimmed : trimmed.substring(trimmed.length() - 12000);
        }

        private static String defaultModel(String configured, String fallback) {
            return configured == null || configured.isBlank() ? fallback : configured.trim();
        }
        private static boolean present(String value) { return value != null && !value.isBlank(); }
    }
}
