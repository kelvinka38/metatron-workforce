package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Durable Telegram transport-receipt continuity.
 *
 * <p>This store is deliberately not a Workforce Objective store. It only proves that an authenticated
 * provider event was durably RECEIVED/ADMITTED before transport acknowledgement and keeps enough
 * correlation data to replay interrupted interaction processing after process replacement. Canonical
 * Objective ownership and ACCEPTED state remain authoritative in Management.</p>
 */
public final class TelegramIngressReceiptStore {
    public enum Status {
        RECEIVED,
        ADMITTED,
        PROCESSING,
        ACCEPTED,
        DELIVERED,
        FAILED,
        DEAD_LETTER
    }

    public record Receipt(
            long updateId,
            long telegramUserId,
            String chatId,
            String text,
            String externalMessageReference,
            Status status,
            int attempts,
            String objectiveId,
            String lastFailure,
            long receivedAtEpochMillis,
            long updatedAtEpochMillis) {
        public Receipt {
            if (updateId < 0 || telegramUserId < 0) throw new IllegalArgumentException("telegram receipt identity invalid");
            Objects.requireNonNull(chatId, "chatId");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(externalMessageReference, "externalMessageReference");
            Objects.requireNonNull(status, "status");
            objectiveId = objectiveId == null ? "" : objectiveId;
            lastFailure = lastFailure == null ? "" : lastFailure;
            if (chatId.isBlank() || text.isBlank() || externalMessageReference.isBlank()) {
                throw new IllegalArgumentException("telegram receipt fields must not be blank");
            }
            if (attempts < 0) throw new IllegalArgumentException("attempts must not be negative");
        }

        Receipt with(Status nextStatus, int nextAttempts, String nextObjectiveId,
                     String nextFailure, long now) {
            return new Receipt(updateId, telegramUserId, chatId, text, externalMessageReference,
                    nextStatus, nextAttempts,
                    nextObjectiveId == null ? objectiveId : nextObjectiveId,
                    nextFailure == null ? lastFailure : nextFailure,
                    receivedAtEpochMillis, now);
        }

        public boolean terminal() {
            return status == Status.DELIVERED || status == Status.DEAD_LETTER;
        }
    }

    public record Snapshot(List<Receipt> receipts) {
        public Snapshot {
            receipts = receipts == null ? List.of() : List.copyOf(receipts);
        }
    }

    private final Path path;
    private final ObjectMapper mapper;
    private final Map<Long, Receipt> receipts = new LinkedHashMap<>();

    public TelegramIngressReceiptStore(Path path, ObjectMapper mapper) {
        this.path = Objects.requireNonNull(path, "path");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
        load();
    }

    /** Idempotently records RECEIVED and fs-level atomic persistence before provider HTTP acknowledgement. */
    public synchronized Receipt receive(long updateId, long telegramUserId, String chatId, String text) {
        Receipt existing = receipts.get(updateId);
        if (existing != null) return existing;
        long now = System.currentTimeMillis();
        Receipt receipt = new Receipt(updateId, telegramUserId, chatId, text,
                "telegram:update:" + updateId, Status.RECEIVED, 0, "", "", now, now);
        receipts.put(updateId, receipt);
        persist();
        return receipt;
    }

    /** Separates authenticated/validated ADMITTED from transport RECEIVED. */
    public synchronized Receipt admit(long updateId) {
        Receipt current = required(updateId);
        if (current.terminal() || current.status() == Status.ACCEPTED) return current;
        if (current.status() == Status.ADMITTED || current.status() == Status.PROCESSING) return current;
        Receipt next = current.with(Status.ADMITTED, current.attempts(), null, "", System.currentTimeMillis());
        receipts.put(updateId, next);
        persist();
        return next;
    }

    /** Claims one bounded processing attempt; a process crash leaves PROCESSING recoverable on restart. */
    public synchronized Receipt claim(long updateId, int maxAttempts) {
        Receipt current = required(updateId);
        if (current.terminal() || current.status() == Status.ACCEPTED) return current;
        if (current.attempts() >= maxAttempts) {
            Receipt dead = current.with(Status.DEAD_LETTER, current.attempts(), null,
                    current.lastFailure().isBlank() ? "processing_attempt_limit_reached" : current.lastFailure(),
                    System.currentTimeMillis());
            receipts.put(updateId, dead);
            persist();
            return dead;
        }
        Receipt next = current.with(Status.PROCESSING, current.attempts() + 1, null, "", System.currentTimeMillis());
        receipts.put(updateId, next);
        persist();
        return next;
    }

    /** Correlates the Human-facing acceptance response with the canonical Management Objective. */
    public synchronized Receipt accepted(long updateId, String objectiveId) {
        Objects.requireNonNull(objectiveId, "objectiveId");
        if (objectiveId.isBlank()) throw new IllegalArgumentException("objectiveId must not be blank");
        Receipt current = required(updateId);
        Receipt next = current.with(Status.ACCEPTED, current.attempts(), objectiveId.trim(), "", System.currentTimeMillis());
        receipts.put(updateId, next);
        persist();
        return next;
    }

    public synchronized Receipt delivered(long updateId) {
        Receipt current = required(updateId);
        Receipt next = current.with(Status.DELIVERED, current.attempts(), null, "", System.currentTimeMillis());
        receipts.put(updateId, next);
        persist();
        return next;
    }

    public synchronized Receipt failed(long updateId, RuntimeException failure, int maxAttempts) {
        Objects.requireNonNull(failure, "failure");
        Receipt current = required(updateId);
        Status status = current.attempts() >= maxAttempts ? Status.DEAD_LETTER : Status.FAILED;
        Receipt next = current.with(status, current.attempts(), null, failureMessage(failure), System.currentTimeMillis());
        receipts.put(updateId, next);
        persist();
        return next;
    }

    /**
     * Telegram flood control (429) says nothing about the interaction: the Objective is already admitted and
     * only the Work Card could not be sent yet. Keep the receipt ACCEPTED (delivery replay only, no attempt
     * consumed) instead of burning the bounded attempts in milliseconds and dead-lettering the Work Card
     * (production 2026-09-24, update 103338021: DEAD_LETTER 1 s after admission, card never delivered).
     */
    public synchronized Receipt deliveryDeferred(long updateId, RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        Receipt current = required(updateId);
        if (current.objectiveId().isBlank()) throw new IllegalStateException("telegram_delivery_deferral_requires_objective");
        Receipt next = current.with(Status.ACCEPTED, current.attempts(), null, failureMessage(failure), System.currentTimeMillis());
        receipts.put(updateId, next);
        persist();
        return next;
    }

    /**
     * Returns dead-lettered receipts whose Objective was admitted and whose only failure was Telegram flood
     * control to ACCEPTED, so their Work Card is delivered once the flood-control window has elapsed. Only
     * receipts dead-lettered at or after {@code notBeforeEpochMillis} qualify; older ones stay retained.
     */
    public synchronized int reviveFloodControlDeadLetters(long notBeforeEpochMillis) {
        int revived = 0;
        for (Receipt receipt : new ArrayList<>(receipts.values())) {
            if (receipt.status() == Status.DEAD_LETTER && !receipt.objectiveId().isBlank()
                    && receipt.updatedAtEpochMillis() >= notBeforeEpochMillis
                    && isFloodControlFailure(receipt.lastFailure())) {
                receipts.put(receipt.updateId(), receipt.with(Status.ACCEPTED, receipt.attempts(), null,
                        receipt.lastFailure(), System.currentTimeMillis()));
                revived++;
            }
        }
        if (revived > 0) persist();
        return revived;
    }

    static boolean isFloodControlFailure(String failure) {
        return failure != null && failure.contains("telegram_error=429");
    }

    private static String failureMessage(RuntimeException failure) {
        String message = failure.getClass().getSimpleName() + ":" + String.valueOf(failure.getMessage());
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    public synchronized Receipt find(long updateId) {
        return receipts.get(updateId);
    }

    /** RECEIVED/ADMITTED/PROCESSING/FAILED are replayable. ACCEPTED waits only for delivery replay. */
    public synchronized List<Receipt> recoverable(int maxAttempts) {
        List<Receipt> result = new ArrayList<>();
        for (Receipt receipt : receipts.values()) {
            if (receipt.status() == Status.ACCEPTED ||
                    ((receipt.status() == Status.RECEIVED || receipt.status() == Status.ADMITTED ||
                            receipt.status() == Status.PROCESSING || receipt.status() == Status.FAILED)
                            && receipt.attempts() < maxAttempts)) {
                result.add(receipt);
            }
        }
        result.sort(Comparator.comparingLong(Receipt::receivedAtEpochMillis));
        return List.copyOf(result);
    }

    public synchronized long count(Status status) {
        return receipts.values().stream().filter(r -> r.status() == status).count();
    }

    private Receipt required(long updateId) {
        Receipt receipt = receipts.get(updateId);
        if (receipt == null) throw new IllegalStateException("telegram_receipt_missing:" + updateId);
        return receipt;
    }

    private void load() {
        if (!Files.exists(path)) return;
        try {
            Snapshot snapshot = mapper.readValue(path.toFile(), Snapshot.class);
            for (Receipt receipt : snapshot.receipts()) receipts.put(receipt.updateId(), receipt);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load telegram ingress state: " + path, failure);
        }
    }

    private void persist() {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new Snapshot(new ArrayList<>(receipts.values())));
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cannot persist telegram ingress state: " + path, failure);
        }
    }
}
