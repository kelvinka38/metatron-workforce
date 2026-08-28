package com.metatron.workforce.phase7;

import java.util.List;

public interface ReviewStateStore {
    List<InstitutionalReview> load();
    void save(List<InstitutionalReview> reviews);
}
