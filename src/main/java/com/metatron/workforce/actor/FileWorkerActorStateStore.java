package com.metatron.workforce.actor;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Incremental per-Worker JSON persistence for elastic actor state/mailboxes.
 * One actor update never rewrites the whole Worker fleet.
 */
public final class FileWorkerActorStateStore implements WorkerActorStateStore {
    private final Path root;
    private final Path actorsDir;
    private final Path mailboxesDir;
    private final ObjectMapper json;

    public FileWorkerActorStateStore(Path root, ObjectMapper objectMapper) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.actorsDir = this.root.resolve("actors");
        this.mailboxesDir = this.root.resolve("mailboxes");
        this.json = Objects.requireNonNull(objectMapper, "objectMapper").copy().findAndRegisterModules();
        try {
            Files.createDirectories(this.actorsDir);
            Files.createDirectories(this.mailboxesDir);
        } catch (IOException e) {
            throw new IllegalStateException("worker actor state directory unavailable", e);
        }
    }

    @Override
    public synchronized Snapshot load() {
        Map<String, WorkerActorSnapshot> actors = new LinkedHashMap<>();
        Map<String, List<WorkerActorMessage>> mailboxes = new LinkedHashMap<>();
        try {
            if (Files.exists(actorsDir)) {
                try (var files = Files.list(actorsDir)) {
                    files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .forEach(path -> {
                                try {
                                    WorkerActorSnapshot actor = json.readValue(path.toFile(), WorkerActorSnapshot.class);
                                    actors.put(actor.workerId(), actor);
                                } catch (IOException e) {
                                    throw new StoreReadFailure(e);
                                }
                            });
                }
            }
            if (Files.exists(mailboxesDir)) {
                try (var files = Files.list(mailboxesDir)) {
                    files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .forEach(path -> {
                                try {
                                    MailboxFile mailbox = json.readValue(path.toFile(), MailboxFile.class);
                                    mailboxes.put(mailbox.workerId(), mailbox.messages() == null ? List.of() : List.copyOf(mailbox.messages()));
                                } catch (IOException e) {
                                    throw new StoreReadFailure(e);
                                }
                            });
                }
            }
            return new Snapshot(actors, mailboxes);
        } catch (StoreReadFailure failure) {
            throw new IllegalStateException("worker actor state unreadable: " + root, failure.getCause());
        } catch (IOException failure) {
            throw new IllegalStateException("worker actor state unreadable: " + root, failure);
        }
    }

    @Override
    public synchronized void saveActor(WorkerActorSnapshot actor) {
        Objects.requireNonNull(actor, "actor");
        writeAtomic(actorPath(actor.workerId()), actor);
    }

    @Override
    public synchronized void saveMailbox(String workerId, List<WorkerActorMessage> messages) {
        String clean = require(workerId, "workerId");
        writeAtomic(mailboxPath(clean), new MailboxFile(clean, messages == null ? List.of() : List.copyOf(messages)));
    }

    private void writeAtomic(Path target, Object value) {
        try {
            Path parent = target.getParent();
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, ".worker-actor-", ".tmp");
            Files.writeString(temp, json.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot persist worker actor state: " + target, e);
        }
    }

    private Path actorPath(String workerId) {
        return actorsDir.resolve(hash(workerId) + ".json");
    }

    private Path mailboxPath(String workerId) {
        return mailboxesDir.resolve(hash(workerId) + ".json");
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(require(value, "workerId").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("worker actor persistence key failure", impossible);
        }
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }

    private record MailboxFile(String workerId, List<WorkerActorMessage> messages) {}
    private static final class StoreReadFailure extends RuntimeException {
        StoreReadFailure(Throwable cause) { super(cause); }
    }
}
