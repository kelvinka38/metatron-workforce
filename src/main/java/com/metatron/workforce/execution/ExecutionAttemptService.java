package com.metatron.workforce.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Canonical Execution-owned lease/fencing lifecycle for retryable runtime attempts. */
public final class ExecutionAttemptService {
    private final ExecutionAttemptStore store;
    private final Map<String,ExecutionAttempt> attempts=new LinkedHashMap<>();
    public ExecutionAttemptService(){this(new InMemoryExecutionAttemptStore());}
    public ExecutionAttemptService(ExecutionAttemptStore store){this.store=Objects.requireNonNull(store); attempts.putAll(store.load());}

    public synchronized ExecutionAttempt begin(String dispatchId,String objectiveId,String stepId,String workerId,
            String assignmentRef,String authorizationRef,String runtimeId,int attemptNumber,Duration lease,Instant at){
        Objects.requireNonNull(lease); Objects.requireNonNull(at); if(lease.isZero()||lease.isNegative()) throw new IllegalArgumentException("lease must be positive");
        String logical=logical(objectiveId,stepId);
        long token=attempts.values().stream().filter(a->logical(a.objectiveId(),a.stepId()).equals(logical)).mapToLong(ExecutionAttempt::fencingToken).max().orElse(0)+1;
        for(var e:new ArrayList<>(attempts.entrySet())){ExecutionAttempt a=e.getValue(); if(logical(a.objectiveId(),a.stepId()).equals(logical)&&!a.terminal()) attempts.put(e.getKey(),copy(a,ExecutionAttempt.Status.FENCED,a.leaseExpiresAt(),a.heartbeatAt(),a.checkpointRef(),"superseded-by-attempt:"+token,at));}
        String id=dispatchId+":execution-attempt:"+attemptNumber+":fence:"+token;
        ExecutionAttempt existing=attempts.get(id); if(existing!=null){ExecutionAttemptContext.bind(existing);return existing;}
        ExecutionAttempt a=new ExecutionAttempt(id,dispatchId,objectiveId,stepId,workerId,assignmentRef,authorizationRef,runtimeId,attemptNumber,token,ExecutionAttempt.Status.LEASED,at.plus(lease),at,"","",at,at);
        attempts.put(id,a); persist(); ExecutionAttemptContext.bind(a); return a;
    }

    public synchronized ExecutionAttempt heartbeat(String id,long token,Duration extension,Instant at){
        Objects.requireNonNull(extension); if(extension.isZero()||extension.isNegative()) throw new IllegalArgumentException("extension must be positive");
        ExecutionAttempt a=requireCurrent(id,token,at); ExecutionAttempt n=copy(a,ExecutionAttempt.Status.RUNNING,at.plus(extension),at,a.checkpointRef(),"",at); attempts.put(id,n); persist(); return n;
    }

    public synchronized ExecutionAttempt checkpoint(String id,long token,String checkpointRef,Instant at){
        if(checkpointRef==null||checkpointRef.isBlank()) throw new IllegalArgumentException("checkpointRef required");
        ExecutionAttempt a=requireCurrent(id,token,at); ExecutionAttempt n=copy(a,ExecutionAttempt.Status.RUNNING,a.leaseExpiresAt(),at,checkpointRef,"",at); attempts.put(id,n); persist(); return n;
    }

    public synchronized ExecutionAttempt succeed(String id,long token,Instant at){
        ExecutionAttempt a=requireCurrent(id,token,at); ExecutionAttempt n=copy(a,ExecutionAttempt.Status.SUCCEEDED,a.leaseExpiresAt(),a.heartbeatAt(),a.checkpointRef(),"",at); attempts.put(id,n); persist(); ExecutionAttemptContext.clearIf(id); return n;
    }

    public synchronized ExecutionAttempt fail(String id,long token,String failure,Instant at){
        if(failure==null||failure.isBlank()) throw new IllegalArgumentException("failure required");
        ExecutionAttempt a=requireOwned(id,token); if(a.terminal()){ExecutionAttemptContext.clearIf(id);return a;} ExecutionAttempt n=copy(a,ExecutionAttempt.Status.FAILED,a.leaseExpiresAt(),a.heartbeatAt(),a.checkpointRef(),failure,at); attempts.put(id,n); persist(); ExecutionAttemptContext.clearIf(id); return n;
    }

    public synchronized List<ExecutionAttempt> reconcileExpired(Instant at){
        List<ExecutionAttempt> abandoned=new ArrayList<>();
        for(var e:new ArrayList<>(attempts.entrySet())){ExecutionAttempt a=e.getValue(); if(!a.terminal()&&!a.leaseExpiresAt().isAfter(at)){ExecutionAttempt n=copy(a,ExecutionAttempt.Status.ABANDONED,a.leaseExpiresAt(),a.heartbeatAt(),a.checkpointRef(),"lease-expired",at); attempts.put(e.getKey(),n); abandoned.add(n);ExecutionAttemptContext.clearIf(a.attemptId());}}
        if(!abandoned.isEmpty()) persist(); return List.copyOf(abandoned);
    }

    /**
     * Founder-directed admin override (2026-09-14): unlike reconcileExpired(), this ignores
     * leaseExpiresAt entirely and abandons every currently non-terminal attempt. Added after manual
     * production incident response: 75 attempts stayed non-terminal indefinitely (their leases kept
     * being valid per their own terms even though nothing was actively working on them -- confirmed
     * waitingExecutions=0/admittedExecutions=0 system-wide for hours), and their backing workspace
     * directories had already been directly removed from the volume during disk-pressure triage, so
     * there was no remaining work product to protect. This is intentionally blunt and is not a
     * substitute for reconcileExpired() in normal operation -- it is an explicit admin action for
     * exactly this "system is idle, these will never resolve on their own, and there is nothing left
     * to lose" situation.
     */
    public synchronized List<ExecutionAttempt> forceAbandonAllNonTerminal(String reason,Instant at){
        if(reason==null||reason.isBlank()) throw new IllegalArgumentException("reason required");
        List<ExecutionAttempt> abandoned=new ArrayList<>();
        for(var e:new ArrayList<>(attempts.entrySet())){ExecutionAttempt a=e.getValue(); if(!a.terminal()){ExecutionAttempt n=copy(a,ExecutionAttempt.Status.ABANDONED,a.leaseExpiresAt(),a.heartbeatAt(),a.checkpointRef(),reason,at); attempts.put(e.getKey(),n); abandoned.add(n);ExecutionAttemptContext.clearIf(a.attemptId());}}
        if(!abandoned.isEmpty()) persist(); return List.copyOf(abandoned);
    }


    public synchronized Optional<ExecutionAttempt> find(String id){return Optional.ofNullable(attempts.get(id));}
    public synchronized List<ExecutionAttempt> all(){return List.copyOf(attempts.values());}

    /** Read-only lookup of the most recent SUCCEEDED attempt for a logical objective+step, for callers
     * (e.g. independent Observation) that must locate a completed attempt's durable workspace without
     * claiming current ownership of it. */
    public synchronized Optional<ExecutionAttempt> latestSucceededForStep(String objectiveId,String stepId){
        String logical=logical(objectiveId,stepId);
        return attempts.values().stream()
                .filter(a->logical(a.objectiveId(),a.stepId()).equals(logical))
                .filter(a->a.status()==ExecutionAttempt.Status.SUCCEEDED)
                .max(Comparator.comparingLong(ExecutionAttempt::fencingToken));
    }

    /**
     * Shared current-attempt validation used by workspace/resource substrates. This extends the
     * existing attempt authority; callers must not reproduce fencing logic independently.
     */
    public synchronized ExecutionAttempt requireCurrent(String id,long token,Instant at){
        Objects.requireNonNull(at,"at");
        ExecutionAttempt a=requireOwned(id,token); if(a.terminal()) throw new IllegalStateException("attempt terminal: "+a.status());
        long current=attempts.values().stream().filter(x->logical(x.objectiveId(),x.stepId()).equals(logical(a.objectiveId(),a.stepId()))).mapToLong(ExecutionAttempt::fencingToken).max().orElse(token);
        if(current!=token){fence(a,"stale-fencing-token",at); throw new SecurityException("stale execution attempt fenced");}
        if(!a.leaseExpiresAt().isAfter(at)){fence(a,"lease-expired",at); throw new SecurityException("expired execution lease fenced");}
        return attempts.getOrDefault(id,a);
    }

    public synchronized boolean current(String id,long token,Instant at){
        try { requireCurrent(id,token,at); return true; }
        catch (RuntimeException denied) { return false; }
    }

    private ExecutionAttempt requireOwned(String id,long token){ExecutionAttempt a=attempts.get(id); if(a==null) throw new IllegalArgumentException("execution attempt not found: "+id); if(a.fencingToken()!=token) throw new SecurityException("execution fencing token mismatch"); return a;}
    private void fence(ExecutionAttempt a,String reason,Instant at){attempts.put(a.attemptId(),copy(a,ExecutionAttempt.Status.FENCED,a.leaseExpiresAt(),a.heartbeatAt(),a.checkpointRef(),reason,at)); persist(); ExecutionAttemptContext.clearIf(a.attemptId());}
    private static ExecutionAttempt copy(ExecutionAttempt a,ExecutionAttempt.Status s,Instant lease,Instant hb,String checkpoint,String failure,Instant at){return new ExecutionAttempt(a.attemptId(),a.dispatchId(),a.objectiveId(),a.stepId(),a.workerId(),a.assignmentRef(),a.authorizationRef(),a.runtimeId(),a.attemptNumber(),a.fencingToken(),s,lease,hb,checkpoint,failure,a.createdAt(),at);}
    private static String logical(String objective,String step){return objective+"#"+step;}
    private void persist(){store.save(attempts);}
}
