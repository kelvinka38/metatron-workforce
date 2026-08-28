package com.metatron.workforce.work;

import java.util.List;

public interface WorkStateStore {
    List<InstitutionalWork> load();
    void save(List<InstitutionalWork> work);
}
