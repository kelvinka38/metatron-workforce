package com.metatron.workforce.phase5;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class StaffingService {
    private final Map<String, StaffingRequest> requests = new ConcurrentHashMap<>();

    public synchronized StaffingRequest detect(String id, String objectiveRef, String organizationContextId,
            String managerWorkerId, String capabilityRef, double required, double available, Instant at) {
        StaffingRequest request = new StaffingRequest(id, objectiveRef, organizationContextId, managerWorkerId,
                capabilityRef, required, available, StaffingRequest.Status.OPEN, null, at, at);
        if (request.gap() <= 0) throw new IllegalArgumentException("staffing request requires a positive capacity gap");
        if (requests.putIfAbsent(id, request) != null) throw new IllegalStateException("staffing request already exists");
        return request;
    }

    public synchronized StaffingRequest propose(String id, String proposalRef, Instant at) {
        requireText(proposalRef, "proposalRef");
        return transition(id, StaffingRequest.Status.PROPOSED, proposalRef, at);
    }

    public synchronized StaffingRequest resolve(String id, String participationOrAssignmentRef, Instant at) {
        requireText(participationOrAssignmentRef, "resolutionRef");
        StaffingRequest old = get(id);
        if (old.status() != StaffingRequest.Status.OPEN && old.status() != StaffingRequest.Status.PROPOSED && old.status() != StaffingRequest.Status.ESCALATED)
            throw new IllegalStateException("staffing request is not resolvable");
        return transition(id, StaffingRequest.Status.RESOLVED, participationOrAssignmentRef, at);
    }

    public synchronized StaffingRequest escalate(String id, String escalationRef, Instant at) {
        requireText(escalationRef, "escalationRef");
        return transition(id, StaffingRequest.Status.ESCALATED, escalationRef, at);
    }

    public StaffingRequest get(String id) { return Optional.ofNullable(requests.get(id)).orElseThrow(() -> new NoSuchElementException("staffing request not found")); }
    public List<StaffingRequest> openForOrganization(String organizationContextId) {
        return requests.values().stream().filter(r -> r.organizationContextId().equals(organizationContextId))
                .filter(r -> r.status() != StaffingRequest.Status.RESOLVED && r.status() != StaffingRequest.Status.CANCELLED).toList();
    }

    private StaffingRequest transition(String id, StaffingRequest.Status status, String ref, Instant at) {
        StaffingRequest old = get(id);
        if (old.status() == StaffingRequest.Status.RESOLVED || old.status() == StaffingRequest.Status.CANCELLED)
            throw new IllegalStateException("terminal staffing request cannot transition");
        StaffingRequest next = new StaffingRequest(old.staffingRequestId(), old.objectiveRef(), old.organizationContextId(),
                old.requestedByWorkerId(), old.capabilityRef(), old.requiredCapacity(), old.availableCapacity(), status,
                ref, old.createdAt(), Objects.requireNonNull(at));
        requests.put(id, next); return next;
    }
    private static void requireText(String value,String field){ if(value==null||value.isBlank()) throw new IllegalArgumentException(field+" required"); }
}
