package com.metatron.workforce.phase5;

import java.util.List;

public interface WorkScheduleStateStore {
    List<WorkSchedule> load();
    void save(List<WorkSchedule> schedules);
}
