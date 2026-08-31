package com.metatron.workforce.management;

import java.time.Instant;
import java.util.Objects;

/** Durable management outbox entry committed with the Workforce state transition that created it. */
public record ManagementOutboxMessage(
        String messageId,
        String messageType,
        String subjectId,
        String correlationId,
        String causationId,
        String idempotencyKey,
        String payload,
        Status status,
        Instant createdAt) {

    public ManagementOutboxMessage {
        requireText(messageId, "messageId");
        requireText(messageType, "messageType");
        requireText(subjectId, "subjectId");
        requireText(correlationId, "correlationId");
        causationId = causationId == null ? "" : causationId;
        requireText(idempotencyKey, "idempotencyKey");
        payload = payload == null ? "" : payload;
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public enum Status { PENDING, PUBLISHED }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
