package com.metatron.workforce.phase5;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class WorkScheduleService {
    private final Map<String, WorkSchedule> schedules = new ConcurrentHashMap<>();
    private final WorkScheduleStateStore store;

    public WorkScheduleService() { this(new WorkScheduleStateStore() {
        private List<WorkSchedule> state = List.of();
        public List<WorkSchedule> load(){ return state; }
        public void save(List<WorkSchedule> schedules){ state = List.copyOf(schedules); }
    }); }

    public WorkScheduleService(WorkScheduleStateStore store) {
        this.store = Objects.requireNonNull(store);
        store.load().forEach(s -> schedules.put(s.scheduleId(), s));
    }

    public synchronized WorkSchedule schedule(WorkSchedule candidate, double workerCapacity) {
        Objects.requireNonNull(candidate);
        if (!Double.isFinite(workerCapacity) || workerCapacity < 0) throw new IllegalArgumentException("workerCapacity invalid");
        double overlapping = schedules.values().stream()
                .filter(s -> s.workerId().equals(candidate.workerId()))
                .filter(s -> s.status() != WorkSchedule.Status.CANCELLED && s.status() != WorkSchedule.Status.COMPLETED)
                .filter(s -> overlaps(s.start(), s.end(), candidate.start(), candidate.end()))
                .mapToDouble(WorkSchedule::committedCapacity).sum();
        if (overlapping + candidate.committedCapacity() > workerCapacity)
            throw new IllegalStateException("schedule would overcommit worker capacity");
        if (schedules.putIfAbsent(candidate.scheduleId(), candidate) != null) throw new IllegalStateException("schedule already exists");
        persist(); return candidate;
    }

    public synchronized WorkSchedule transition(String id, WorkSchedule.Status target) {
        WorkSchedule old = get(id);
        if (old.status() == WorkSchedule.Status.COMPLETED || old.status() == WorkSchedule.Status.CANCELLED)
            throw new IllegalStateException("terminal schedule cannot transition");
        WorkSchedule next = new WorkSchedule(old.scheduleId(), old.assignmentRef(), old.workerId(), old.start(), old.end(),
                old.committedCapacity(), Objects.requireNonNull(target), old.evidenceRef());
        schedules.put(id, next); persist(); return next;
    }

    public WorkSchedule get(String id) { return Optional.ofNullable(schedules.get(id)).orElseThrow(() -> new NoSuchElementException("schedule not found")); }
    public List<WorkSchedule> forWorker(String workerId) { return schedules.values().stream().filter(s -> s.workerId().equals(workerId)).toList(); }
    public boolean validAt(String scheduleId, Instant at) { return get(scheduleId).activeAt(at); }
    private void persist(){ store.save(List.copyOf(schedules.values())); }
    private static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) { return aStart.isBefore(bEnd) && bStart.isBefore(aEnd); }
}
