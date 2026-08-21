package com.metatron.workforce.phase3;

import java.util.Objects;

public record ActorRef(String actorId, ActorType type) {
    public ActorRef {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(type, "type");
        if (actorId.isBlank()) throw new IllegalArgumentException("actorId must not be blank");
    }

    public enum ActorType { HUMAN, WORKER, ORGANIZATIONAL_GROUP }
}
