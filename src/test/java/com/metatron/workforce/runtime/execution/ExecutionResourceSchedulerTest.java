package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionResourceSchedulerTest {
    @TempDir Path temp;

    @Test void thousandQueuedAttemptsRemainDurableWhileRealCapacityStaysBounded(){
        Instant t=Instant.parse("2026-09-12T00:00:00Z");
        ExecutionAttemptService attempts=new ExecutionAttemptService();
        ExecutionWorkspaceManager workspaces=new ExecutionWorkspaceManager(temp,attempts,new InMemoryExecutionWorkspaceBindingStore());
        ExecutionResourceManager resources=new ExecutionResourceManager(attempts,new InMemoryResourceStateStore(),Map.of("build:jvm",2.0));
        ExecutionResourceScheduler scheduler=new ExecutionResourceScheduler(attempts,resources,workspaces,new InMemoryExecutionQueueStore(),1000,Duration.ofMinutes(10));
        List<ExecutionAttempt> all=new ArrayList<>();
        for(int i=0;i<1000;i++){
            ExecutionAttempt a=attempts.begin("d"+i,"objective:scale","step:"+i,"worker","assignment:"+i,"auth","runtime:"+i,1,Duration.ofHours(1),t);
            all.add(a);
            ResourceClaim claim=new ResourceClaim("build:"+i,a.attemptId(),"build:jvm",ResourceClaim.ResourceClass.BUILD,ResourceClaim.Mode.CAPACITY,1,"slot",true,"",Map.of());
            scheduler.submit(new ExecutionResourceAdmissionRequest("request:"+i,a.attemptId(),a.fencingToken(),a.objectiveId(),"autonomy-schedule:1",50,t.plus(Duration.ofHours(1)),List.of(claim),"",t.plusMillis(i)),t.plusMillis(i));
        }
        assertTrue(scheduler.admitNext(t.plusSeconds(2)).isPresent());
        assertTrue(scheduler.admitNext(t.plusSeconds(2)).isPresent());
        assertTrue(scheduler.admitNext(t.plusSeconds(2)).isEmpty());
        assertEquals(2,scheduler.admittedCount());
        assertEquals(998,scheduler.waitingCount());
        assertEquals(1000,all.size());
        assertEquals(1000,attempts.all().size(),"durable execution state must not imply resident process count");
    }

    @Test void upstreamDecisionReferenceIsMandatoryAndAgingCanAdvanceOlderWork(){
        Instant t=Instant.parse("2026-09-12T00:00:00Z");
        ExecutionAttemptService attempts=new ExecutionAttemptService();
        ExecutionWorkspaceManager workspaces=new ExecutionWorkspaceManager(temp,attempts,new InMemoryExecutionWorkspaceBindingStore());
        ExecutionResourceManager resources=new ExecutionResourceManager(attempts,new InMemoryResourceStateStore(),Map.of("compute:slot",1.0));
        ExecutionResourceScheduler scheduler=new ExecutionResourceScheduler(attempts,resources,workspaces,new InMemoryExecutionQueueStore(),10,Duration.ofMinutes(5));
        ExecutionAttempt a=attempts.begin("da","o1","sa","w","aa","z","ra",1,Duration.ofHours(1),t);
        ResourceClaim ca=new ResourceClaim("ca",a.attemptId(),"compute:slot",ResourceClaim.ResourceClass.COMPUTE,ResourceClaim.Mode.CAPACITY,1,"slot",true,"",Map.of());
        assertThrows(IllegalArgumentException.class,()->new ExecutionResourceAdmissionRequest("r",a.attemptId(),a.fencingToken(),a.objectiveId(),"",1,t.plusSeconds(10),List.of(ca),"",t));
    }
}
