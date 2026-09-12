package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentInferenceConsumptionLedgerTest {
    @TempDir Path tempDir;

    @Test
    void recordsSurviveRestartAndWorkerExternalQueryIsDurable() throws Exception {
        Path ledgerPath = tempDir.resolve("inference-ledger.jsonl");
        ObjectMapper mapper = new ObjectMapper();
        PersistentInferenceConsumptionLedger first = new PersistentInferenceConsumptionLedger(ledgerPath, mapper);

        first.record(record("worker-internal", IntelligenceOriginType.WORKER, IntelligenceComputeOwner.METATRON_OWNED));
        first.record(record("human-external", IntelligenceOriginType.HUMAN, IntelligenceComputeOwner.EXTERNAL_PAID));

        assertEquals(0L, first.workerExternalPaidCount());
        assertTrue(Files.size(ledgerPath) > 0L);

        PersistentInferenceConsumptionLedger restarted = new PersistentInferenceConsumptionLedger(ledgerPath, mapper);
        assertEquals(2, restarted.records().size());
        assertEquals(0L, restarted.workerExternalPaidCount());

        restarted.record(record("worker-external-forbidden-proof", IntelligenceOriginType.WORKER,
                IntelligenceComputeOwner.EXTERNAL_PAID));
        PersistentInferenceConsumptionLedger secondRestart = new PersistentInferenceConsumptionLedger(ledgerPath, mapper);
        assertEquals(3, secondRestart.records().size());
        assertEquals(1L, secondRestart.workerExternalPaidCount());
    }

    @Test
    void malformedDurableEvidenceFailsClosed() throws Exception {
        Path ledgerPath = tempDir.resolve("bad.jsonl");
        Files.writeString(ledgerPath, "{not-json}\n");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new PersistentInferenceConsumptionLedger(ledgerPath, new ObjectMapper()));
        assertTrue(failure.getMessage().contains("inference_ledger_malformed_line"));
    }

    private static InferenceConsumptionRecord record(
            String requestId,
            IntelligenceOriginType originType,
            IntelligenceComputeOwner owner) {
        return new InferenceConsumptionRecord(
                requestId,
                originType,
                originType == IntelligenceOriginType.WORKER ? "WORKER-TEST" : "human:test",
                originType == IntelligenceOriginType.WORKER ? "WORKER-TEST" : "",
                "OBJ-1",
                "ASG-1",
                "STEP-1",
                "ATT-1",
                owner,
                owner == IntelligenceComputeOwner.METATRON_OWNED ? "metatron-node-test" : "external:OPENAI",
                "model-test",
                10,
                5,
                20,
                "SUCCESS",
                owner == IntelligenceComputeOwner.EXTERNAL_PAID ? "provider-request-1" : "",
                Instant.parse("2026-09-12T00:00:00Z"));
    }
}
