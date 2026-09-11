package com.metatron.workforce.deliberation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Universal, role-independent deliberation state machine for live Worker interaction.
 * It decides what kind of move is appropriate before provider cognition. Roles contribute
 * domain grounding and quality criteria elsewhere; they never replace this control loop.
 */
public final class WorkerDeliberationRuntime {
    private static final TypeReference<Map<String, WorkerDeliberationState>> MAP_TYPE = new TypeReference<>() {};
    private final Path path;
    private final ObjectMapper json;
    private final Map<String, WorkerDeliberationState> states = new LinkedHashMap<>();

    public WorkerDeliberationRuntime(Path path, ObjectMapper objectMapper) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.json = Objects.requireNonNull(objectMapper, "objectMapper").copy().findAndRegisterModules();
        load();
    }

    public synchronized Directive prepare(String workerId, String message, String context) {
        Objects.requireNonNull(workerId, "workerId");
        String human = humanMessage(message, context);
        WorkerDeliberationState previous = states.getOrDefault(workerId, WorkerDeliberationState.initial(workerId));
        Decision decision = decide(previous, human);
        WorkerDeliberationState next = new WorkerDeliberationState(
                workerId,
                decision.objectiveSummary(),
                decision.stage(),
                decision.move(),
                decision.intent(),
                decision.contextSufficiency(),
                decision.assumptions(),
                decision.openQuestions(),
                decision.decisions(),
                previous.revisionCount() + (decision.move() == WorkerNextMove.REVISE ? 1 : 0),
                previous.clarificationCount() + (decision.move() == WorkerNextMove.CLARIFY ? 1 : 0),
                Instant.now());
        states.put(workerId, next);
        persist();
        return new Directive(next, renderDirective(next));
    }

    public synchronized void completeTurn(String workerId, WorkerNextMove move) {
        WorkerDeliberationState current = states.get(workerId);
        if (current == null) return;
        WorkerWorkStage stage = switch (move) {
            case CLARIFY, PROPOSE -> WorkerWorkStage.WAITING_FOR_HUMAN;
            case CONVERSE -> WorkerWorkStage.IDLE;
            case ESCALATE -> WorkerWorkStage.ESCALATED;
            case ACT, PLAN, INSPECT, CRITIQUE, REVISE, VERIFY, DELIVER -> WorkerWorkStage.DELIVERED;
        };
        states.put(workerId, new WorkerDeliberationState(
                current.workerId(), current.objectiveSummary(), stage, current.nextMove(), current.intent(),
                current.contextSufficiency(), current.assumptions(), current.openQuestions(), current.decisions(),
                current.revisionCount(), current.clarificationCount(), Instant.now()));
        persist();
    }

    public synchronized WorkerDeliberationState state(String workerId) {
        return states.getOrDefault(workerId, WorkerDeliberationState.initial(workerId));
    }

    private static Decision decide(WorkerDeliberationState previous, String message) {
        String text = message == null ? "" : message.trim();
        String folded = fold(text);
        if (folded.isBlank()) {
            return Decision.of(previous.objectiveSummary(), WorkerWorkStage.CLARIFYING, WorkerNextMove.CLARIFY,
                    "INSTRUCTION", "INSUFFICIENT", List.of(), List.of("What outcome should I work toward?"), List.of());
        }

        if (containsAny(folded, "cancel", "stop this", "dung lai", "huy", "bo task")) {
            return Decision.of(previous.objectiveSummary(), WorkerWorkStage.CANCELLED, WorkerNextMove.CONVERSE,
                    "CANCEL", "SUFFICIENT", List.of(), List.of(), List.of("Current work cancelled by Human"));
        }
        if (containsAny(folded, "pause", "tam dung", "khoan lam")) {
            return Decision.of(previous.objectiveSummary(), WorkerWorkStage.PAUSED, WorkerNextMove.CONVERSE,
                    "INTERRUPTION", "SUFFICIENT", List.of(), List.of(), List.of("Current work paused by Human"));
        }

        boolean awaitingObjectiveInput = previous.stage() == WorkerWorkStage.WAITING_FOR_HUMAN
                && previous.objectiveSummary() != null && !previous.objectiveSummary().isBlank();
        if (awaitingObjectiveInput) {
            return Decision.of(previous.objectiveSummary(), WorkerWorkStage.REVISING, WorkerNextMove.REVISE,
                    "FEEDBACK", "SUFFICIENT", List.of(), List.of(), List.of("Human feedback incorporated into active work"));
        }

        if (isSimpleDirectAction(folded, text)) {
            return Decision.of(summary(text), WorkerWorkStage.EXECUTING, WorkerNextMove.ACT,
                    "INSTRUCTION", "SUFFICIENT", List.of(), List.of(), List.of());
        }

        boolean objective = looksLikeObjective(folded);
        boolean complex = objective && looksComplex(folded);
        boolean detailed = isDetailed(text, folded);
        if (objective && complex && !detailed) {
            WorkerNextMove move = previous.clarificationCount() > 0 ? WorkerNextMove.PROPOSE : WorkerNextMove.CLARIFY;
            WorkerWorkStage stage = move == WorkerNextMove.CLARIFY ? WorkerWorkStage.CLARIFYING : WorkerWorkStage.PROPOSING;
            return Decision.of(summary(text), stage, move, "OBJECTIVE", "INSUFFICIENT", List.of(),
                    List.of("Material outcome-shaping context is still missing"), List.of());
        }
        if (objective && complex) {
            return Decision.of(summary(text), WorkerWorkStage.PLANNING, WorkerNextMove.PLAN,
                    "OBJECTIVE", "SUFFICIENT", List.of(), List.of(), List.of());
        }
        if (objective) {
            return Decision.of(summary(text), WorkerWorkStage.EXECUTING, WorkerNextMove.ACT,
                    "OBJECTIVE", detailed ? "SUFFICIENT" : "PARTIAL", List.of(), List.of(), List.of());
        }
        return Decision.of("", WorkerWorkStage.DISCUSSING, WorkerNextMove.CONVERSE,
                looksLikeFeedback(folded) ? "FEEDBACK" : "QUESTION", "SUFFICIENT", List.of(), List.of(), List.of());
    }

    private static String renderDirective(WorkerDeliberationState state) {
        return """
                UNIVERSAL WORKER DELIBERATION CONTROL
                This control is role-independent and authoritative for how to handle this turn.
                current_stage=%s
                intent=%s
                context_sufficiency=%s
                next_move=%s
                active_objective=%s

                Mandatory behavior:
                - MESSAGE != OBJECTIVE; MODEL OUTPUT != COMPLETION; DRAFT != FINAL; ROLE != COGNITIVE LOOP.
                - Do not expose hidden chain-of-thought. Give concise useful rationale, questions, options, progress or deliverables only.
                - If next_move=CLARIFY: do NOT produce the final deliverable. Ask at most 3 high-value outcome-shaping questions; do not ask for facts already present in context/memory.
                - If next_move=PROPOSE: do NOT pretend work is final. Offer 2-3 materially different directions/trade-offs and recommend one so the Human can align quickly.
                - If next_move=PLAN: internally form a proportional plan, create the work, critique it against objective/constraints, revise material defects, then verify before presenting a final deliverable. Do not add ceremony or unnecessary questions.
                - If next_move=ACT: perform the requested low-risk/direct work without unnecessary clarification; verify obvious constraints before presenting it.
                - If next_move=REVISE: treat the Human message as feedback on the active objective/draft; preserve continuity, revise the existing work, and do not reset as a new unrelated prompt.
                - If next_move=CONVERSE: converse naturally and intelligently; do not force a work lifecycle when the Human is simply discussing or asking a question.
                - Never claim external execution/completion without the evidence required by existing execution and Observation gates.
                """.formatted(
                state.stage(), state.intent(), state.contextSufficiency(), state.nextMove(),
                state.objectiveSummary() == null ? "" : state.objectiveSummary());
    }

    private static boolean isSimpleDirectAction(String folded, String raw) {
        if (raw.length() > 600) return false;
        return startsWithAny(folded,
                "rewrite ", "rephrase ", "translate ", "summarize ", "proofread ", "shorten ",
                "viet lai ", "dich ", "tom tat ", "sua ngu phap ", "rut gon ");
    }

    private static boolean looksLikeObjective(String folded) {
        return startsWithAny(folded,
                "write ", "create ", "make ", "build ", "design ", "develop ", "prepare ", "produce ",
                "draft ", "plan ", "fix ", "implement ", "research ", "analyze ", "audit ", "compose ",
                "viet ", "tao ", "lam ", "xay ", "thiet ke ", "soan ", "lap ke hoach ", "sua ",
                "trien khai ", "nghien cuu ", "phan tich ", "kiem tra ", "sang tac ");
    }

    private static boolean looksComplex(String folded) {
        return containsAny(folded,
                "strategy", "campaign", "system", "architecture", "proposal", "roadmap", "business plan",
                "song", "music", "brand", "product", "project", "research", "audit", "implement", "build",
                "chien luoc", "he thong", "kien truc", "de xuat", "ke hoach", "bai nhac", "thuong hieu",
                "san pham", "du an", "nghien cuu", "trien khai", "xay dung");
    }

    private static boolean isDetailed(String raw, String folded) {
        if (raw.length() >= 260) return true;
        long lines = raw.lines().filter(line -> !line.isBlank()).count();
        if (lines >= 4) return true;
        int separators = count(raw, ':') + count(raw, ';') + count(raw, ',');
        if (separators >= 5) return true;
        return containsAny(folded, "requirements", "constraints", "audience", "target", "deadline", "budget",
                "yeu cau", "doi tuong", "muc tieu", "ngan sach", "deadline") && raw.length() >= 120;
    }

    private static boolean looksLikeFeedback(String folded) {
        return startsWithAny(folded, "no ", "not ", "change ", "make it ", "revise ", "khong ", "doi ", "sua ", "lam no ");
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) if (value.startsWith(prefix)) return true;
        return false;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static int count(String value, char needle) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == needle) count++;
        return count;
    }

    private static String summary(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 300 ? compact : compact.substring(0, 300);
    }

    private static String humanMessage(String supplied, String context) {
        if (supplied != null && !supplied.isBlank()) return supplied;
        if (context == null) return "";
        int marker = context.indexOf("HUMAN MESSAGE");
        if (marker < 0) return "";
        String tail = context.substring(marker + "HUMAN MESSAGE".length());
        int next = tail.indexOf("CONVERSATION CONTEXT");
        return (next >= 0 ? tail.substring(0, next) : tail).trim();
    }

    private static String fold(String value) {
        String source = value == null ? "" : value;
        String decomposed = Normalizer.normalize(source, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT)
                .replace('đ', 'd')
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private synchronized void load() {
        if (!Files.exists(path)) return;
        try {
            Map<String, WorkerDeliberationState> loaded = json.readValue(Files.readString(path, StandardCharsets.UTF_8), MAP_TYPE);
            if (loaded != null) states.putAll(loaded);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load worker deliberation state: " + path, failure);
        }
    }

    private synchronized void persist() {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, ".worker-deliberation-", ".tmp");
            Files.writeString(temp, json.writerWithDefaultPrettyPrinter().writeValueAsString(states), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cannot persist worker deliberation state: " + path, failure);
        }
    }

    public record Directive(WorkerDeliberationState state, String instructions) {}

    private record Decision(
            String objectiveSummary,
            WorkerWorkStage stage,
            WorkerNextMove move,
            String intent,
            String contextSufficiency,
            List<String> assumptions,
            List<String> openQuestions,
            List<String> decisions) {
        static Decision of(String objective, WorkerWorkStage stage, WorkerNextMove move, String intent,
                           String sufficiency, List<String> assumptions, List<String> questions, List<String> decisions) {
            return new Decision(objective == null ? "" : objective, stage, move, intent, sufficiency,
                    List.copyOf(assumptions), List.copyOf(questions), List.copyOf(decisions));
        }
    }
}
