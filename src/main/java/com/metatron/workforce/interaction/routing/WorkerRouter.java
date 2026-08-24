package com.metatron.workforce.interaction.routing;

import java.util.Map;
import java.util.Objects;

/** Explicit route table; unknown intent/target is rejected rather than guessed. */
public final class WorkerRouter {
    private final Map<String, String> routes;

    public WorkerRouter(Map<String, String> routes) {
        this.routes = Map.copyOf(Objects.requireNonNull(routes, "routes"));
    }

    public WorkerRoute route(Intent intent) {
        Objects.requireNonNull(intent, "intent");
        String key = (intent.action() + ":" + intent.target()).toLowerCase();
        String worker = routes.get(key);
        if (worker == null) throw new IllegalArgumentException("no_worker_route");
        return new WorkerRoute(worker, intent);
    }
}
