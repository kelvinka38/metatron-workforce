package com.metatron.workforce.workplace;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Atomic file-backed Meeting Room store on the institutional runtime state volume. */
public final class PersistentMeetingStore {
    private final Path root;
    private final ObjectMapper json;

    public PersistentMeetingStore(Path root, ObjectMapper json) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.json = Objects.requireNonNull(json, "json");
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create meeting store", e);
        }
    }

    public synchronized MeetingRecord save(MeetingRecord meeting) {
        Objects.requireNonNull(meeting, "meeting");
        Path target = path(meeting.meetingId());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), meeting);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return meeting;
        } catch (IOException e) {
            throw new IllegalStateException("cannot persist meeting " + meeting.meetingId(), e);
        }
    }

    public Optional<MeetingRecord> find(String meetingId) {
        Path file = path(meetingId);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(json.readValue(file.toFile(), MeetingRecord.class));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read meeting " + meetingId, e);
        }
    }

    public Optional<MeetingRecord> findByExternalMessageReference(String externalMessageReference) {
        if (externalMessageReference == null || externalMessageReference.isBlank()) return Optional.empty();
        return list().stream()
                .filter(m -> externalMessageReference.equals(m.externalMessageReference()))
                .findFirst();
    }

    public List<MeetingRecord> list() {
        List<MeetingRecord> meetings = new ArrayList<>();
        try (var files = Files.list(root)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString).reversed())
                    .forEach(file -> {
                        try { meetings.add(json.readValue(file.toFile(), MeetingRecord.class)); }
                        catch (IOException e) { throw new IllegalStateException("cannot read meeting " + file, e); }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("cannot list meetings", e);
        }
        return List.copyOf(meetings);
    }

    private Path path(String meetingId) {
        if (meetingId == null || !meetingId.matches("meeting:[a-zA-Z0-9._:-]+")) {
            throw new IllegalArgumentException("invalid meetingId");
        }
        Path file = root.resolve(meetingId.replace(':', '_') + ".json").normalize();
        if (!file.startsWith(root)) throw new SecurityException("meeting path escaped store root");
        return file;
    }
}
