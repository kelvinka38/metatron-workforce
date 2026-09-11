package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Canonical infrastructure/shared-resource ownership below ExecutionAttempt authority.
 * Attempt fencing and resource fencing are intentionally independent layers.
 */
public final class ExecutionResourceManager {
    private final ExecutionAttemptService attempts;
    private final ResourceStateStore store;
    private final Map<String,Double> capacities;
    private final Map<String,ResourceLease> leases = new LinkedHashMap<>();
    private final Map<String,ResourceFencingState> fencing = new LinkedHashMap<>();
    private long capacityVersion = 1;

    public ExecutionResourceManager(ExecutionAttemptService attempts, ResourceStateStore store, Map<String,Double> capacities) {
        this.attempts = Objects.requireNonNull(attempts,"attempts");
        this.store = Objects.requireNonNull(store,"store");
        this.capacities = new LinkedHashMap<>();
        if(capacities!=null) capacities.forEach((k,v)->{
            if(k==null||k.isBlank()||v==null||!Double.isFinite(v)||v<=0) throw new IllegalArgumentException("invalid resource capacity");
            this.capacities.put(k.trim(),v);
        });
        ResourceStateStore.Snapshot snapshot=store.load(); leases.putAll(snapshot.leases()); fencing.putAll(snapshot.fencing());
    }

    public synchronized ResourceAssessment assess(String attemptId,long attemptFence,List<ResourceClaim> claims,Instant at) {
        attempts.requireCurrent(attemptId,attemptFence,at); reconcileExpiredInternal(at,false);
        List<ResourceClaim> normalized=normalizeClaims(attemptId,claims);
        Map<String,String> blockers=new LinkedHashMap<>();
        for(ResourceClaim claim:normalized){
            String blocker=blockerFor(claim,attemptId,at);
            if(!blocker.isBlank() && claim.required()) blockers.put(claim.claimId(),blocker);
        }
        return new ResourceAssessment(blockers.isEmpty(),normalized,blockers);
    }

    public synchronized ResourceGrant acquire(String attemptId,long attemptFence,String ownerActor,List<ResourceClaim> claims,Duration ttl,Instant at){
        ExecutionAttempt attempt=attempts.requireCurrent(attemptId,attemptFence,at);
        Objects.requireNonNull(ttl,"ttl"); if(ttl.isZero()||ttl.isNegative()) throw new IllegalArgumentException("resource lease ttl must be positive");
        String owner=require(ownerActor,"ownerActor");
        reconcileExpiredInternal(at,false);
        List<ResourceClaim> normalized=normalizeClaims(attemptId,claims);
        ResourceAssessment assessment=assess(attemptId,attemptFence,normalized,at);
        if(!assessment.grantable()) throw new IllegalStateException("RESOURCE_CONFLICT: "+assessment.blockers());
        List<ResourceLease> granted=new ArrayList<>();
        for(ResourceClaim claim:normalized){
            Optional<ResourceLease> reusable=leases.values().stream().filter(l->l.attemptId().equals(attemptId)&&l.claimId().equals(claim.claimId())&&l.resourceId().equals(claim.resourceId())&&l.activeAt(at)).findFirst();
            if(reusable.isPresent()){granted.add(reusable.get());continue;}
            ResourceFencingState state=fencing.getOrDefault(claim.resourceId(),new ResourceFencingState(claim.resourceId(),0,1,Set.of(),at));
            long resourceFence=claim.exclusive()?state.currentFencingToken()+1:Math.max(1,state.currentFencingToken());
            Set<String> active=new LinkedHashSet<>(state.activeLeaseIds());
            String leaseId="resource-lease:"+UUID.randomUUID(); active.add(leaseId);
            ResourceLease lease=new ResourceLease(leaseId,claim.claimId(),claim.resourceId(),attemptId,owner,claim.resourceClass(),claim.mode(),claim.quantity(),resourceFence,1,at,at.plus(ttl),at,ResourceLease.Status.ACTIVE);
            leases.put(leaseId,lease);
            fencing.put(claim.resourceId(),new ResourceFencingState(claim.resourceId(),resourceFence,state.stateVersion()+1,active,at));
            granted.add(lease);
        }
        capacityVersion++; persist();
        return new ResourceGrant(attempt.attemptId(),attemptFence,granted);
    }

    public synchronized List<ResourceLease> renew(String attemptId,long attemptFence,Collection<String> leaseIds,Duration ttl,Instant at){
        attempts.requireCurrent(attemptId,attemptFence,at); Objects.requireNonNull(ttl); if(ttl.isZero()||ttl.isNegative())throw new IllegalArgumentException("ttl must be positive");
        List<ResourceLease> out=new ArrayList<>();
        for(String id:leaseIds){
            ResourceLease l=requireLease(id); if(!l.attemptId().equals(attemptId))throw new SecurityException("RESOURCE_LEASE_REQUIRED: attempt mismatch");
            requireLeaseActive(l,at);
            ResourceLease n=copy(l,l.stateVersion()+1,at.plus(ttl),at,ResourceLease.Status.ACTIVE); leases.put(id,n); out.add(n);
        }
        persist(); return List.copyOf(out);
    }

    /** Validate both attempt fence and resource fence immediately before a protected effect. */
    public synchronized ResourceLease requireCurrent(String attemptId,long attemptFence,String leaseId,long resourceFence,String resourceId,Instant at){
        attempts.requireCurrent(attemptId,attemptFence,at);
        ResourceLease lease=requireLease(leaseId);
        if(!lease.attemptId().equals(attemptId)||!lease.resourceId().equals(resourceId)) throw new SecurityException("RESOURCE_LEASE_REQUIRED: ownership mismatch");
        requireLeaseActive(lease,at);
        if(lease.mode()==ResourceClaim.Mode.READ_SHARED||lease.mode()==ResourceClaim.Mode.CAPACITY) throw new SecurityException("RESOURCE_LEASE_REQUIRED: mutation requires exclusive claim");
        ResourceFencingState state=fencing.get(resourceId);
        if(state==null||state.currentFencingToken()!=resourceFence||lease.fencingToken()!=resourceFence) throw new SecurityException("RESOURCE_FENCED: stale resource fencing token");
        return lease;
    }

    public synchronized void release(String attemptId,Collection<String> leaseIds,Instant at){
        Objects.requireNonNull(leaseIds,"leaseIds");
        boolean changed=false;
        for(String id:leaseIds){
            ResourceLease l=leases.get(id); if(l==null||l.status()!=ResourceLease.Status.ACTIVE)continue;
            if(!l.attemptId().equals(attemptId))throw new SecurityException("RESOURCE_LEASE_REQUIRED: release owner mismatch");
            leases.put(id,copy(l,l.stateVersion()+1,l.expiresAt(),at,ResourceLease.Status.RELEASED));
            removeActiveLease(l.resourceId(),id,at); changed=true;
        }
        if(changed){capacityVersion++;persist();}
    }

    public synchronized List<ResourceLease> reconcileExpired(Instant at){return reconcileExpiredInternal(at,true);}

    public synchronized List<ResourceLease> activeLeases(){Instant now=Instant.now();return leases.values().stream().filter(l->l.activeAt(now)).toList();}
    public synchronized Optional<ResourceLease> findLease(String leaseId){return Optional.ofNullable(leases.get(leaseId));}
    public synchronized Optional<ResourceFencingState> fencingState(String resourceId){return Optional.ofNullable(fencing.get(resourceId));}

    public synchronized List<ResourceCapacitySnapshot> capacitySnapshot(Instant at){
        List<ResourceCapacitySnapshot> out=new ArrayList<>();
        for(var entry:capacities.entrySet()){
            double used=leases.values().stream().filter(l->l.resourceId().equals(entry.getKey())&&l.activeAt(at)&&l.mode()==ResourceClaim.Mode.CAPACITY).mapToDouble(ResourceLease::quantity).sum();
            ResourceClaim.ResourceClass klass=leases.values().stream().filter(l->l.resourceId().equals(entry.getKey())).map(ResourceLease::resourceClass).findFirst().orElse(ResourceClaim.ResourceClass.COMPUTE);
            out.add(new ResourceCapacitySnapshot(klass,entry.getKey(),entry.getValue(),used,0,Math.max(0,entry.getValue()-used),at,capacityVersion));
        }
        return List.copyOf(out);
    }

    private List<ResourceLease> reconcileExpiredInternal(Instant at,boolean persistEvenIfChanged){
        List<ResourceLease> changed=new ArrayList<>();
        for(ResourceLease lease:new ArrayList<>(leases.values())){
            if(lease.status()!=ResourceLease.Status.ACTIVE)continue;
            boolean expired=!lease.expiresAt().isAfter(at);
            boolean attemptCurrent=false;
            try{ExecutionAttempt a=attempts.find(lease.attemptId()).orElse(null);attemptCurrent=a!=null&&!a.terminal()&&a.leaseExpiresAt().isAfter(at);}catch(RuntimeException ignored){}
            if(expired||!attemptCurrent){
                ResourceLease.Status status=expired?ResourceLease.Status.EXPIRED:ResourceLease.Status.FENCED;
                ResourceLease next=copy(lease,lease.stateVersion()+1,lease.expiresAt(),at,status); leases.put(lease.leaseId(),next); removeActiveLease(lease.resourceId(),lease.leaseId(),at); changed.add(next);
            }
        }
        if(!changed.isEmpty()){capacityVersion++;persist();}
        else if(persistEvenIfChanged){/* no-op: caller requested reconciliation evidence only */}
        return List.copyOf(changed);
    }

    private String blockerFor(ResourceClaim claim,String attemptId,Instant at){
        if(claim.mode()==ResourceClaim.Mode.CAPACITY){
            Double total=capacityFor(claim.resourceId());
            if(total==null)return "RESOURCE_CAPACITY_UNAVAILABLE:no-capacity-policy:"+claim.resourceId();
            double used=leases.values().stream().filter(l->l.resourceId().equals(claim.resourceId())&&l.activeAt(at)&&l.mode()==ResourceClaim.Mode.CAPACITY&&!l.attemptId().equals(attemptId)).mapToDouble(ResourceLease::quantity).sum();
            if(used+claim.quantity()>total+1e-9)return "RESOURCE_CAPACITY_UNAVAILABLE:"+claim.resourceId();
            return "";
        }
        for(ResourceLease held:leases.values()){
            if(!held.activeAt(at)||held.attemptId().equals(attemptId))continue;
            if(!resourceNamesConflict(claim.resourceId(),held.resourceId()))continue;
            boolean bothRead=claim.mode()==ResourceClaim.Mode.READ_SHARED&&held.mode()==ResourceClaim.Mode.READ_SHARED;
            if(!bothRead)return "RESOURCE_CONFLICT:"+held.resourceId()+":held-by:"+held.attemptId();
        }
        return "";
    }

    private Double capacityFor(String resourceId){
        Double exact=capacities.get(resourceId); if(exact!=null)return exact;
        return capacities.entrySet().stream().filter(e->resourceNamesConflict(resourceId,e.getKey())).map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static boolean resourceNamesConflict(String a,String b){return a.equals(b)||a.startsWith(b+":")||b.startsWith(a+":");}
    private List<ResourceClaim> normalizeClaims(String attemptId,List<ResourceClaim> claims){
        if(claims==null||claims.isEmpty())return List.of();
        Map<String,ResourceClaim> byId=new LinkedHashMap<>();
        for(ResourceClaim c:claims){if(!c.attemptId().equals(attemptId))throw new SecurityException("resource claim attempt mismatch");ResourceClaim previous=byId.putIfAbsent(c.claimId(),c);if(previous!=null&&!previous.equals(c))throw new IllegalArgumentException("duplicate claimId with different semantics: "+c.claimId());}
        return List.copyOf(byId.values());
    }
    private ResourceLease requireLease(String id){ResourceLease l=leases.get(id);if(l==null)throw new SecurityException("RESOURCE_LEASE_REQUIRED: "+id);return l;}
    private static void requireLeaseActive(ResourceLease l,Instant at){if(l.status()!=ResourceLease.Status.ACTIVE)throw new SecurityException("RESOURCE_LEASE_REQUIRED: lease not active");if(!l.expiresAt().isAfter(at))throw new SecurityException("RESOURCE_LEASE_EXPIRED: "+l.leaseId());}
    private void removeActiveLease(String resourceId,String leaseId,Instant at){ResourceFencingState s=fencing.get(resourceId);if(s==null)return;Set<String> active=new LinkedHashSet<>(s.activeLeaseIds());active.remove(leaseId);fencing.put(resourceId,new ResourceFencingState(resourceId,s.currentFencingToken(),s.stateVersion()+1,active,at));}
    private static ResourceLease copy(ResourceLease l,long version,Instant expires,Instant heartbeat,ResourceLease.Status status){return new ResourceLease(l.leaseId(),l.claimId(),l.resourceId(),l.attemptId(),l.ownerActor(),l.resourceClass(),l.mode(),l.quantity(),l.fencingToken(),version,l.acquiredAt(),expires,heartbeat,status);}
    private static String require(String v,String f){if(v==null||v.isBlank())throw new IllegalArgumentException(f+" required");return v.trim();}
    private void persist(){store.save(new ResourceStateStore.Snapshot(leases,fencing));}
}
