package com.metatron.workforce.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces the canonical Intelligence boundary in source code.
 * Provider transports may exist only inside the Intelligence/provider adapter domain.
 */
class ProviderBoundaryArchitectureTest {
    private static final List<String> FORBIDDEN = List.of(
            "interaction.llm.LlmProviderRouter",
            "interaction.llm.OpenAiLlmProviderClient",
            "interaction.llm.GoogleLlmProviderClient",
            "interaction.llm.AnthropicLlmProviderClient");

    @Test
    void productionCodeOutsideIntelligenceCannotOwnProviderTransport() throws Exception {
        Path root = Path.of("src/main/java");
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java")).toList()) {
                String normalized = path.toString().replace('\\', '/');
                if (normalized.contains("/interaction/intelligence/")
                        || normalized.contains("/interaction/llm/")) {
                    continue;
                }
                String source = Files.readString(path);
                for (String forbidden : FORBIDDEN) {
                    if (source.contains(forbidden)) {
                        violations.add(normalized + " -> " + forbidden);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "Direct provider ownership outside Intelligence is forbidden: " + violations);
    }
}
