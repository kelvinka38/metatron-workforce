package com.metatron.workforce.work;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InstitutionalWorkAcceptanceTest {
    @TempDir Path temp;

    @Test void workRemainsDistinctAttributedEvidenceBoundAndDurable(){
        Path file=temp.resolve("work.json");
        WorkService first=new WorkService(new FileWorkStateStore(file));
        Instant t=Instant.now();
        first.originate("W1","OBJ1","ORG1","WORKER1","deliver bounded work",t);
        first.linkProposal("W1","PROPOSAL1",t.plusSeconds(1));
        first.assign("W1","ASSIGNMENT1",t.plusSeconds(2));
        first.start("W1",t.plusSeconds(3));
        first.block("W1","EVIDENCE-BLOCK",t.plusSeconds(4));
        first.resume("W1",t.plusSeconds(5));
        assertThrows(IllegalArgumentException.class,()->first.complete("W1","OUTCOME1",List.of(),t.plusSeconds(6)));
        first.complete("W1","OUTCOME1",List.of("EVIDENCE-DONE"),t.plusSeconds(7));

        WorkService replacement=new WorkService(new FileWorkStateStore(file));
        InstitutionalWork recovered=replacement.get("W1");
        assertEquals(InstitutionalWork.Status.COMPLETED,recovered.status());
        assertEquals("OBJ1",recovered.objectiveRef());
        assertEquals("PROPOSAL1",recovered.proposalRef());
        assertEquals("ASSIGNMENT1",recovered.assignmentRef());
        assertEquals("OUTCOME1",recovered.outcomeRef());
        assertTrue(recovered.evidenceRefs().contains("EVIDENCE-BLOCK"));
        assertTrue(recovered.evidenceRefs().contains("EVIDENCE-DONE"));
        assertThrows(IllegalStateException.class,()->replacement.start("W1",Instant.now()));
    }
}
