package com.metatron.workforce.phase3;

@FunctionalInterface
public interface AuthorizationPolicy {
    AuthorizationContext authorize(ActorRef actor, ActorRef target, String organizationContextId);
}
