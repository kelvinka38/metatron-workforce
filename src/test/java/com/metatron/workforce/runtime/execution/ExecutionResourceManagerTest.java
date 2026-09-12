package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionResourceManagerTest {
    private static ExecutionAttempt attempt(ExecutionAttemptService attempts,String dispatch,String step,Instant at){
        return attempts.begin(dispatch,"objective:r",step,"worker:r","assignment:"+step,"auth:r","runtime:"+step,1,Duration.ofHours(1),at);
    }
    private static ResourceClaim claim(String id,String attempt,String resource,ResourceClaim.ResourceClass klass,ResourceClaim.Mode mode,double qty){
        return new ResourceClaim(id,attempt,resource,klass,mode,qty,"slot",true,"",Map.of());
    }

    @Test void resourceFenceRejectsResurrectedOldOwnerAfterLeaseExpiry(){
        Instant t=Instant.parse("2026-09-12T00:00:00Z");
        ExecutionAttemptService attempts=new ExecutionAttemptService();
        ExecutionAttempt a=attempt(attempts,"da","a",t); ExecutionAttempt b=attempt(attempts,"db","b",t);
        ExecutionResourceManager manager=new ExecutionResourceManager(attempts,new InMemoryResourceStateStore(),Map.of());
        ResourceGrant ga=manager.acquire(a.attemptId(),a.fencingToken(),a.workerId(),List.of(claim("ca",a.attemptId(),"prod:workforce",ResourceClaim.ResourceClass.ENVIRONMENT,ResourceClaim.Mode.LEASE_EXCLUSIVE,1)),Duration.ofSeconds(5),t);
        ResourceLease la=ga.leases().getFirst();
        assertEquals(1,la.fencingToken());
        assertThrows(IllegalStateException.class,()->manager.acquire(b.attemptId(),b.fencingToken(),b.workerId(),List.of(claim("cb",b.attemptId(),"prod:workforce",ResourceClaim.ResourceClass.ENVIRONMENT,ResourceClaim.Mode.LEASE_EXCLUSIVE,1)),Duration.ofSeconds(5),t.plusSeconds(1)));

        manager.reconcileExpired(t.plusSeconds(6));
        ResourceLease lb=manager.acquire(b.attemptId(),b.fencingToken(),b.workerId(),List.of(claim("cb",b.attemptId(),"prod:workforce",ResourceClaim.ResourceClass.ENVIRONMENT,ResourceClaim.Mode.LEASE_EXCLUSIVE,1)),Duration.ofSeconds(5),t.plusSeconds(6)).leases().getFirst();
        assertTrue(lb.fencingToken()>la.fencingToken());
        assertThrows(SecurityException.class,()->manager.requireCurrent(a.attemptId(),a.fencingToken(),la.leaseId(),la.fencingToken(),"prod:workforce",t.plusSeconds(7)));
        assertEquals(lb.leaseId(),manager.requireCurrent(b.attemptId(),b.fencingToken(),lb.leaseId(),lb.fencingToken(),"prod:workforce",t.plusSeconds(7)).leaseId());
    }

    @Test void sharedReadsParallelizeAndCapacityBackpressures(){
        Instant t=Instant.parse("2026-09-12T00:00:00Z");
        ExecutionAttemptService attempts=new ExecutionAttemptService();
        ExecutionAttempt a=attempt(attempts,"da","a",t), b=attempt(attempts,"db","b",t), c=attempt(attempts,"dc","c",t);
        ExecutionResourceManager manager=new ExecutionResourceManager(attempts,new InMemoryResourceStateStore(),Map.of("build:jvm",2.0));

        assertDoesNotThrow(()->manager.acquire(a.attemptId(),a.fencingToken(),a.workerId(),List.of(claim("ra",a.attemptId(),"repo:workforce",ResourceClaim.ResourceClass.REPOSITORY,ResourceClaim.Mode.READ_SHARED,1)),Duration.ofMinutes(5),t));
        assertDoesNotThrow(()->manager.acquire(b.attemptId(),b.fencingToken(),b.workerId(),List.of(claim("rb",b.attemptId(),"repo:workforce",ResourceClaim.ResourceClass.REPOSITORY,ResourceClaim.Mode.READ_SHARED,1)),Duration.ofMinutes(5),t));

        manager.acquire(a.attemptId(),a.fencingToken(),a.workerId(),List.of(claim("ba",a.attemptId(),"build:jvm",ResourceClaim.ResourceClass.BUILD,ResourceClaim.Mode.CAPACITY,1)),Duration.ofMinutes(5),t);
        manager.acquire(b.attemptId(),b.fencingToken(),b.workerId(),List.of(claim("bb",b.attemptId(),"build:jvm",ResourceClaim.ResourceClass.BUILD,ResourceClaim.Mode.CAPACITY,1)),Duration.ofMinutes(5),t);
        ResourceAssessment blocked=manager.assess(c.attemptId(),c.fencingToken(),List.of(claim("bc",c.attemptId(),"build:jvm",ResourceClaim.ResourceClass.BUILD,ResourceClaim.Mode.CAPACITY,1)),t.plusSeconds(1));
        assertFalse(blocked.grantable());
        assertTrue(blocked.blockers().get("bc").startsWith("RESOURCE_CAPACITY_UNAVAILABLE"));
        assertEquals(0.0,manager.capacitySnapshot(t.plusSeconds(1)).getFirst().available());
    }
}
