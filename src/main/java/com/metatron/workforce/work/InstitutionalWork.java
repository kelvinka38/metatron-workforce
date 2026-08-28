package com.metatron.workforce.work;

import java.time.Instant;
import java.util.List;

/** Work is distinct from Objective, Proposal, Assignment, Authorization, Execution and queue projection. */
public record InstitutionalWork(
        String workId,
        String objectiveRef,
        String organizationContextId,
        String originatedByWorkerId,
        String description,
        Status status,
        String proposalRef,
        String assignmentRef,
        String outcomeRef,
        List<String> evidenceRefs,
        Instant createdAt,
        Instant updatedAt) {
    public enum Status { ORIGINATED, PROPOSED, ASSIGNED, IN_PROGRESS, BLOCKED, COMPLETED, CANCELLED }
    public InstitutionalWork {
        require(workId,"workId"); require(objectiveRef,"objectiveRef"); require(organizationContextId,"organizationContextId");
        require(originatedByWorkerId,"originatedByWorkerId"); require(description,"description");
        if(status==null||createdAt==null||updatedAt==null) throw new IllegalArgumentException("status/timestamps required");
        evidenceRefs=List.copyOf(evidenceRefs==null?List.of():evidenceRefs);
    }
    public boolean terminal(){ return status==Status.COMPLETED||status==Status.CANCELLED; }
    private static void require(String v,String n){ if(v==null||v.isBlank()) throw new IllegalArgumentException(n+" required"); }
}
