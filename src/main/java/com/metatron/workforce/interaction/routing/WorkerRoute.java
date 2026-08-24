package com.metatron.workforce.interaction.routing;

import java.util.Objects;

public record WorkerRoute(String workerId, Intent intent) {
    public WorkerRoute {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(intent, "intent");
    }
}
