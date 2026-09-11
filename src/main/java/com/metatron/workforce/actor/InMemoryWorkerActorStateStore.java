package com.metatron.workforce.actor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test/local in-memory Worker actor persistence. */
public final class InMemoryWorkerActorStateStore implements WorkerActorStateStore {
    private final Map<String, WorkerActorSnapshot> actors = new LinkedHashMap<>();
    private final Map<String, List<WorkerActorMessage>> mailboxes = new LinkedHashMap<>();

    @Override
    public synchronized Snapshot load() {
        Map<String, List<WorkerActorMessage>> mailboxCopy = new LinkedHashMap<>();
        mailboxes.forEach((workerId, messages) -> mailboxCopy.put(workerId, List.copyOf(messages)));
        return new Snapshot(Map.copyOf(actors), mailboxCopy);
    }

    @Override
    public synchronized void saveActor(WorkerActorSnapshot actor) {
        actors.put(actor.workerId(), actor);
    }

    @Override
    public synchronized void saveMailbox(String workerId, List<WorkerActorMessage> messages) {
        mailboxes.put(workerId, new ArrayList<>(messages == null ? List.of() : messages));
    }

    /** Test-only seed helper. */
    public synchronized void seed(Snapshot snapshot) {
        actors.clear();
        mailboxes.clear();
        actors.putAll(snapshot.actors());
        snapshot.mailboxes().forEach((workerId, messages) -> mailboxes.put(workerId, new ArrayList<>(messages)));
    }
}
