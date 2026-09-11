package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Infrastructure WHEN/WHERE scheduler below AutonomySchedulingService. It never plans Work. */
public final class ExecutionResourceScheduler {
    private final ExecutionAttemptService attempts;
    private final ExecutionResourceManager resources;
    private final ExecutionWorkspaceManager workspaces;
    private final ExecutionQueueStore store;
    private final int maxActivePerObjective;
    private final Duration grantTtl;
    private final Map<String,ExecutionResourceAdmissionRequest> requests=new LinkedHashMap<>();
    private final Map<String,ExecutionResourceAdmissionDecision> decisions=new LinkedHashMap<>();

    public ExecutionResourceScheduler(ExecutionAttemptService attempts,ExecutionResourceManager resources,
                                      ExecutionWorkspaceManager workspaces,ExecutionQueueStore store,
                                      int maxActivePerObjective,Duration grantTtl){
        this.attempts=Objects.requireNonNull(attempts);this.resources=Objects.requireNonNull(resources);this.workspaces=Objects.requireNonNull(workspaces);this.store=Objects.requireNonNull(store);
        if(maxActivePerObjective<1)throw new IllegalArgumentException("maxActivePerObjective must be positive");this.maxActivePerObjective=maxActivePerObjective;
        this.grantTtl=Objects.requireNonNull(grantTtl);if(grantTtl.isZero()||grantTtl.isNegative())throw new IllegalArgumentException("grantTtl must be positive");
        ExecutionQueueStore.Snapshot snapshot=store.load();requests.putAll(snapshot.requests());decisions.putAll(snapshot.decisions());
    }

    public synchronized ExecutionResourceAdmissionDecision submit(ExecutionResourceAdmissionRequest request,Instant at){
        Objects.requireNonNull(request);ExecutionAttempt attempt=attempts.requireCurrent(request.attemptId(),request.attemptFencingToken(),at);
        if(!attempt.objectiveId().equals(request.objectiveId()))throw new SecurityException("resource scheduler objective mismatch");
        if(request.schedulingDecisionRef().isBlank())throw new SecurityException("upstream eligibility/scheduling decision required");
        ExecutionResourceAdmissionRequest existing=requests.putIfAbsent(request.requestId(),request);
        if(existing!=null&&!existing.equals(request))throw new IllegalStateException("resource admission idempotency conflict");
        ExecutionResourceAdmissionDecision decision=decisions.get(request.requestId());
        if(decision!=null)return decision;
        decision=new ExecutionResourceAdmissionDecision(request.requestId(),request.attemptId(),ExecutionResourceAdmissionDecision.Status.WAITING_RESOURCES,"queued",null,"","",at);
        decisions.put(request.requestId(),decision);persist();return decision;
    }

    /** Attempt admission for one already-submitted execution. This never changes WHAT work is eligible. */
    public synchronized ExecutionResourceAdmissionDecision admit(String requestId,Instant at){
        ExecutionResourceAdmissionRequest request=requests.get(requestId);
        if(request==null)throw new IllegalArgumentException("resource admission request not found: "+requestId);
        ExecutionResourceAdmissionDecision current=decisions.get(requestId);
        if(current!=null&&current.status()!=ExecutionResourceAdmissionDecision.Status.WAITING_RESOURCES)return current;
        return tryAdmit(request,at);
    }

    public synchronized Optional<ExecutionResourceAdmissionDecision> admitNext(Instant at){
        List<ExecutionResourceAdmissionRequest> candidates=requests.values().stream()
                .filter(r->{ExecutionResourceAdmissionDecision d=decisions.get(r.requestId());return d==null||d.status()==ExecutionResourceAdmissionDecision.Status.WAITING_RESOURCES;})
                .sorted(Comparator.<ExecutionResourceAdmissionRequest>comparingLong(r->-effectivePriority(r,at)).thenComparing(ExecutionResourceAdmissionRequest::submittedAt).thenComparing(ExecutionResourceAdmissionRequest::requestId))
                .toList();
        for(ExecutionResourceAdmissionRequest request:candidates){
            ExecutionResourceAdmissionDecision decision=tryAdmit(request,at);
            if(decision.status()==ExecutionResourceAdmissionDecision.Status.ADMITTED)return Optional.of(decision);
        }
        return Optional.empty();
    }

    private ExecutionResourceAdmissionDecision tryAdmit(ExecutionResourceAdmissionRequest request,Instant at){
        if(!request.deadline().isAfter(at)){
            ExecutionResourceAdmissionDecision blocked=new ExecutionResourceAdmissionDecision(request.requestId(),request.attemptId(),ExecutionResourceAdmissionDecision.Status.BLOCKED,"deadline-exceeded",null,"","",at);decisions.put(request.requestId(),blocked);persist();return blocked;
        }
        ExecutionAttempt attempt;
        try{attempt=attempts.requireCurrent(request.attemptId(),request.attemptFencingToken(),at);}catch(RuntimeException stale){ExecutionResourceAdmissionDecision blocked=new ExecutionResourceAdmissionDecision(request.requestId(),request.attemptId(),ExecutionResourceAdmissionDecision.Status.BLOCKED,"execution-attempt-not-current",null,"","",at);decisions.put(request.requestId(),blocked);persist();return blocked;}
        long objectiveActive=decisions.values().stream().filter(d->d.status()==ExecutionResourceAdmissionDecision.Status.ADMITTED).map(d->attempts.find(d.attemptId()).orElse(null)).filter(Objects::nonNull).filter(a->a.objectiveId().equals(request.objectiveId())&&!a.terminal()).count();
        if(objectiveActive>=maxActivePerObjective)return recordWaiting(request,"objective-fairness-capacity",at);
        ResourceAssessment assessment=resources.assess(request.attemptId(),request.attemptFencingToken(),request.claims(),at);
        if(!assessment.grantable())return recordWaiting(request,assessment.blockers().values().stream().findFirst().orElse("resource-unavailable"),at);
        ResourceGrant grant;
        try{grant=resources.acquire(request.attemptId(),request.attemptFencingToken(),attempt.workerId(),request.claims(),grantTtl,at);}catch(RuntimeException raced){return recordWaiting(request,"resource-race:"+raced.getClass().getSimpleName(),at);}
        ExecutionWorkspaceBinding workspace=workspaces.allocate(request.attemptId(),request.attemptFencingToken(),at);
        String executor=request.localityHint().isBlank()?"executor:local":request.localityHint();
        ExecutionResourceAdmissionDecision admitted=new ExecutionResourceAdmissionDecision(request.requestId(),request.attemptId(),ExecutionResourceAdmissionDecision.Status.ADMITTED,"resource-grant-acquired",grant,executor,workspace.workspaceId(),at);
        decisions.put(request.requestId(),admitted);persist();return admitted;
    }

    public synchronized void complete(String requestId,Instant at){
        ExecutionResourceAdmissionDecision d=decisions.get(requestId);if(d==null)throw new IllegalArgumentException("resource admission not found: "+requestId);
        if(d.grant()!=null)resources.release(d.attemptId(),d.grant().leases().stream().map(ResourceLease::leaseId).toList(),at);
        decisions.remove(requestId);requests.remove(requestId);persist();
    }

    public synchronized Optional<ExecutionResourceAdmissionDecision> findDecision(String requestId){return Optional.ofNullable(decisions.get(requestId));}
    public synchronized List<ExecutionResourceAdmissionDecision> allDecisions(){return List.copyOf(decisions.values());}
    public synchronized long waitingCount(){return decisions.values().stream().filter(d->d.status()==ExecutionResourceAdmissionDecision.Status.WAITING_RESOURCES).count();}
    public synchronized long admittedCount(){return decisions.values().stream().filter(d->d.status()==ExecutionResourceAdmissionDecision.Status.ADMITTED).count();}

    private ExecutionResourceAdmissionDecision recordWaiting(ExecutionResourceAdmissionRequest request,String reason,Instant at){ExecutionResourceAdmissionDecision previous=decisions.get(request.requestId());if(previous!=null&&previous.status()==ExecutionResourceAdmissionDecision.Status.WAITING_RESOURCES&&previous.reason().equals(reason))return previous;ExecutionResourceAdmissionDecision waiting=new ExecutionResourceAdmissionDecision(request.requestId(),request.attemptId(),ExecutionResourceAdmissionDecision.Status.WAITING_RESOURCES,reason,null,"","",at);decisions.put(request.requestId(),waiting);persist();return waiting;}
    private static long effectivePriority(ExecutionResourceAdmissionRequest r,Instant at){long age=Math.max(0,ChronoUnit.MINUTES.between(r.submittedAt(),at));return (long)r.priority()+Math.min(age,1000);}
    private void persist(){store.save(new ExecutionQueueStore.Snapshot(requests,decisions));}
}
