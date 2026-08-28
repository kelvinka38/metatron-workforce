package com.metatron.workforce.phase5;

import java.util.List;

public interface StaffingStateStore {
    List<StaffingRequest> load();
    void save(List<StaffingRequest> requests);
}
