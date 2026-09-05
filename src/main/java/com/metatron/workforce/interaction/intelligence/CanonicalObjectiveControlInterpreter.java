package com.metatron.workforce.interaction.intelligence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic semantic bridge for the canonical explicit Objective control grammar.
 *
 * <p>This is intentionally narrow. It recognizes only the institutional control forms already
 * treated by the Telegram ingress as requiring Objective materialization. Ordinary Human language
 * remains owned by the frontier semantic boundary.</p>
 */
public final class CanonicalObjectiveControlInterpreter {
    private static final String PREFIX_OBJECTIVE = "take ownership of one objective:";
    private static final String PREFIX_MUTATION = "take ownership of one governed mutation objective";
    private static final Pattern SCOPED_REPOSITORY = Pattern.compile(
            "(?i)\\b(?:against|of|repository|repositories)\\s+(?:https://github\\.com/)?([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)");
    private static final Pattern REPOSITORY = Pattern.compile(
            "(?i)(?:https://github\\.com/)?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:\\.git)?");
    private static final Pattern DO_NOT = Pattern.compile("(?i)\\bdo\\s+not\\s+([^.;]+)");

    private CanonicalObjectiveControlInterpreter() {}

    public static boolean isExplicitObjectiveControl(String humanText) {
        if (humanText == null) return false;
        String normalized = humanText.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return normalized.startsWith(PREFIX_OBJECTIVE)
                || normalized.startsWith(PREFIX_MUTATION + ":")
                || normalized.startsWith(PREFIX_MUTATION + " ");
    }

    public static Optional<NormalizedRequest> interpret(String humanText) {
        if (!isExplicitObjectiveControl(humanText)) return Optional.empty();

        String original = humanText.trim();
        String lower = original.toLowerCase(Locale.ROOT);
        String target = repositoryTargets(original);
        List<String> prohibitions = prohibitions(original);
        List<AnalyticalProtocolType> protocols = new ArrayList<>();
        if (lower.contains("audit")) protocols.add(AnalyticalProtocolType.AUDIT);
        if (lower.contains("risk")) protocols.add(AnalyticalProtocolType.RISK);
        if (lower.contains("verify") || lower.contains("verification")) {
            protocols.add(AnalyticalProtocolType.IMPROVEMENT);
        }

        boolean fresh = lower.contains(" current ") || lower.contains(" latest ")
                || lower.contains(" today ") || lower.contains(" right now ");

        return Optional.of(new NormalizedRequest(
                original,
                target,
                List.of(original),
                IntelligenceDepth.DEEP,
                "evidence-backed completion with independent Observation",
                List.of(),
                prohibitions,
                "",
                "",
                IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE,
                protocols.stream().distinct().toList(),
                DeterministicCapability.NONE,
                List.of(),
                List.of(),
                fresh,
                null,
                null,
                CaseContinuity.NEW,
                ""));
    }

    static String repositoryTargets(String text) {
        Matcher scoped = SCOPED_REPOSITORY.matcher(text == null ? "" : text);
        if (!scoped.find()) return "";
        String seed = scoped.group(1);
        int slash = seed.indexOf('/');
        if (slash <= 0) return "";
        String owner = seed.substring(0, slash).toLowerCase(Locale.ROOT);

        LinkedHashSet<String> targets = new LinkedHashSet<>();
        Matcher all = REPOSITORY.matcher(text);
        while (all.find()) {
            if (!all.group(1).equalsIgnoreCase(owner)) continue;
            targets.add(all.group(1).toLowerCase(Locale.ROOT) + "/" + all.group(2).replaceAll("(?i)\\.git$", "").toLowerCase(Locale.ROOT));
        }
        return String.join(",", targets);
    }

    static List<String> prohibitions(String text) {
        List<String> values = new ArrayList<>();
        Matcher matcher = DO_NOT.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String value = "Do not " + matcher.group(1).trim();
            if (!value.isBlank()) values.add(value);
        }
        return List.copyOf(values);
    }
}
