package com.metatron.workforce.interaction.intelligence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic semantic bridge for explicit institutional Objective controls.
 *
 * <p>This remains intentionally narrow. It recognizes the canonical "Take ownership of one ...
 * Objective" grammar and explicit assignment of work to a named canonical WORKER-* identity.
 * Ordinary Human language remains owned by the frontier semantic boundary.</p>
 */
public final class CanonicalObjectiveControlInterpreter {
    private static final String PREFIX_OBJECTIVE = "take ownership of one objective:";
    private static final Pattern QUALIFIED_OBJECTIVE = Pattern.compile(
            "^take ownership of one (?:governed|bounded(?:-[a-z0-9_-]+)?)(?: [a-z0-9_-]+){0,10} objective(?::|\\.|\\s).*");
    private static final String NAME = "[A-Za-z0-9_.-]*[A-Za-z0-9_-]";
    private static final Pattern SCOPED_REPOSITORY = Pattern.compile(
            "(?i)\\b(?:against|of|repository|repositories)\\s+(?:https://github\\.com/)?(" + NAME + "/" + NAME + ")(?:\\.git)?(?=$|[,.;:)\\s])");
    private static final Pattern REPOSITORY = Pattern.compile(
            "(?i)(?:https://github\\.com/)?(" + NAME + ")/(" + NAME + ")(?:\\.git)?(?=$|[,.;:)\\s])");
    private static final Pattern EXPLICIT_URI_TARGET = Pattern.compile(
            "(?i)\\b(?:against\\s+(?:exact\\s+)?target|target)\\s+([a-z][a-z0-9+.-]*://\\S+)");
    private static final Pattern WORKER_REF = Pattern.compile("(?i)\\b(WORKER-[A-Z0-9._:-]+)\\b");
    private static final Pattern DO_NOT = Pattern.compile("(?i)\\bdo\\s+not\\s+([^.;]+)");

    private CanonicalObjectiveControlInterpreter() {}

    public static boolean isExplicitObjectiveControl(String humanText) {
        if (humanText == null) return false;
        String normalized = humanText.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return normalized.startsWith(PREFIX_OBJECTIVE)
                || QUALIFIED_OBJECTIVE.matcher(normalized).matches()
                || isExplicitWorkerAssignment(humanText);
    }

    public static Optional<NormalizedRequest> interpret(String humanText) {
        if (!isExplicitObjectiveControl(humanText)) return Optional.empty();

        String original = humanText.trim();
        String lower = original.toLowerCase(Locale.ROOT);
        String target;
        if (isExplicitWorkerAssignment(original)) {
            target = workerTarget(original);
        } else {
            target = explicitUriTarget(original);
            if (target.isBlank()) target = repositoryTargets(original);
        }
        List<String> prohibitions = prohibitions(original);
        List<AnalyticalProtocolType> protocols = new ArrayList<>();
        if (lower.contains("audit")) protocols.add(AnalyticalProtocolType.AUDIT);
        if (lower.contains("risk")) protocols.add(AnalyticalProtocolType.RISK);
        if (lower.contains("verify") || lower.contains("verification")) {
            protocols.add(AnalyticalProtocolType.IMPROVEMENT);
        }

        boolean fresh = lower.contains(" current ") || lower.contains(" latest ")
                || lower.contains(" today ") || lower.contains(" right now ");
        String requestedOutput = isExplicitWorkerAssignment(original)
                ? "evidence-backed durable work product from the explicitly assigned canonical Worker"
                : "evidence-backed completion with independent Observation";

        return Optional.of(new NormalizedRequest(
                original,
                target,
                List.of(original),
                IntelligenceDepth.DEEP,
                requestedOutput,
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

    static boolean isExplicitWorkerAssignment(String text) {
        if (text == null || text.isBlank() || workerTarget(text).isBlank()) return false;
        String q = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return q.startsWith("assign ")
                || q.startsWith("delegate ")
                || q.startsWith("give ") && q.contains(" task")
                || q.startsWith("giao ")
                || q.contains(" giao viec ")
                || q.contains(" giao việc ");
    }

    static String workerTarget(String text) {
        Matcher matcher = WORKER_REF.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : "";
    }

    static String explicitUriTarget(String text) {
        Matcher matcher = EXPLICIT_URI_TARGET.matcher(text == null ? "" : text);
        if (!matcher.find()) return "";
        String value = matcher.group(1).trim();
        while (!value.isEmpty() && ".,;:)]}".indexOf(value.charAt(value.length() - 1)) >= 0) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
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
