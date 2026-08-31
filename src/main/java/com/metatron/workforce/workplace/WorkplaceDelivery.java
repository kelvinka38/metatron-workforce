package com.metatron.workforce.workplace;

import java.time.Instant;
import java.util.Objects;

/** Durable delivery projection; channel adapters own transport effects. */
public record WorkplaceDelivery(
        String deliveryId,
        String objectiveId,
        String conversationRef,
        String humanId,
        String channel,
        String authorizationRef,
        String payloadReference,
        Status status,
        String externalDeliveryRef,
        String failure,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status { PENDING, DELIVERED, FAILED }

    public WorkplaceDelivery {
        require(deliveryId, "deliveryId");
        require(objectiveId, "objectiveId");
        require(conversationRef, "conversationRef");
        require(humanId, "humanId");
        require(channel, "channel");
        require(authorizationRef, "authorizationRef");
        require(payloadReference, "payloadReference");
        Objects.requireNonNull(status, "status");
        externalDeliveryRef = externalDeliveryRef == null ? "" : externalDeliveryRef.trim();
        failure = failure == null ? "" : failure.trim();
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }
}
