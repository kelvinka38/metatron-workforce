package com.metatron.workforce.interaction.routing;

import java.util.Objects;

/** Channel-neutral orchestration boundary. Execution is delegated to the selected worker. */
public final class InteractionOrchestrator {
    private final IntentParser parser;
    private final WorkerRouter router;

    public InteractionOrchestrator(IntentParser parser, WorkerRouter router) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.router = Objects.requireNonNull(router, "router");
    }

    public WorkerRoute resolve(String text) {
        Intent intent = parser.parse(text);
        if ("unknown".equals(intent.action()) || intent.target().isBlank())
            throw new IllegalArgumentException("intent_unresolved");
        return router.route(intent);
    }
}
