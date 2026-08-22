package com.metatron.workforce.phase9;

import java.util.Objects;

public final class ObservationBoundary {
    private ObservationBoundary() {
    }

    public static BoundaryResult apply(
            Phase9BoundaryService service,
            BoundaryRequest request,
            BoundaryDecision decision,
            Object output) {
        Objects.requireNonNull(service);
        return service.observation(request, decision, output);
    }
}
