package com.metatron.workforce.actor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Incremental persistence boundary for Worker actor state and per-Worker mailboxes. */
public interface WorkerActorStateStore {
    Snapshot load();
    void saveActor(WorkerActorSnapshot actor);
    void saveMailbox(String workerId, List<WorkerActorMessage> messages);

    record Snapshot(
            Map<String, WorkerActorSnapshot> actors,
            Map<String, List<WorkerActorMessage>> mailboxes) {
        public Snapshot {
            Map<String, WorkerActorSnapshot> actorCopy = new LinkedHashMap<>();
            if (actors != null) actorCopy.putAll(actors);
            actors = Map.copyOf(actorCopy);

            Map<String, List<WorkerActorMessage>> mailboxCopy = new LinkedHashMap<>();
            if (mailboxes != null) {
                mailboxes.forEach((workerId, messages) -> mailboxCopy.put(
                        workerId, messages == null ? List.of() : List.copyOf(messages)));
            }
            mailboxes = Map.copyOf(mailboxCopy);
        }

        public static Snapshot empty() { return new Snapshot(Map.of(), Map.of()); }
    }
}
