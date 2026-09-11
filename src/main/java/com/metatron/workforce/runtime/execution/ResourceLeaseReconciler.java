package com.metatron.workforce.runtime.execution;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Reconciles expired/fenced canonical resource leases without creating a second ownership model. */
public final class ResourceLeaseReconciler {
    private final ExecutionResourceManager resources;

    public ResourceLeaseReconciler(ExecutionResourceManager resources) {
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public List<ResourceLease> reconcile(Instant at) {
        return resources.reconcileExpired(Objects.requireNonNull(at, "at"));
    }
}
