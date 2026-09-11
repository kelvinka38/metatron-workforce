package com.metatron.workforce.actor;

import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Routes every Worker cognition request through that Worker's independent actor work lane. */
public final class ActorScopedWorkerIntelligenceService implements WorkerIntelligenceService {
    private static final Pattern OBJECTIVE = Pattern.compile("(?im)^objective_id\\s*[=:]\\s*([^\\s]+)");
    private static final Pattern ASSIGNMENT = Pattern.compile("(?im)^(?:assignment_reference|assignment_id)\\s*[=:]\\s*([^\\s]+)");
    private static final Pattern STEP = Pattern.compile("(?im)^step_id\\s*[=:]\\s*([^\\s]+)");

    private final WorkerIntelligenceService delegate;
    private final WorkerActorRuntime actors;

    public ActorScopedWorkerIntelligenceService(WorkerIntelligenceService delegate, WorkerActorRuntime actors) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    @Override
    public Response reason(Request request) {
        Objects.requireNonNull(request, "request");
        String context = request.context();
        return actors.runTurn(
                request.requester(),
                WorkerActorMessage.Type.SYSTEM,
                "workforce:intelligence",
                extract(OBJECTIVE, context),
                extract(ASSIGNMENT, context),
                extract(STEP, context),
                request.capability(),
                summarize(request.instructions()),
                () -> delegate.reason(request));
    }

    private static String extract(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String summarize(String value) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 512 ? compact : compact.substring(0, 512);
    }
}
