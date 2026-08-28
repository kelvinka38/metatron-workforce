package com.metatron.workforce.execution;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionGuidanceGateTest {

    @Test
    void assignmentWithoutGuidanceRemainsBackwardCompatible() {
        Assignment assignment = new Assignment("a-1", "w-1");
        ExecutionRequest request = new ExecutionRequest(
                "e-1", assignment, new Authorization("auth-1", "w-1"), Instant.now());

        assertEquals(ExecutionState.ADMITTED, new ExecutionAdmissionService().admit(request));
    }

    @Test
    void requiredGuidanceFailsClosedWhenNoReceiptExists() {
        GuidanceRequirement requirement = new GuidanceRequirement(
                "gateway-sot",
                "/public/docs/gateway/SOT.md",
                "github:kelvinka38/metatron-institution/06_GATEWAY/SOT.md@b6426c31dbbf52e8cc3668fc551602066803d21a");
        Assignment assignment = new Assignment("a-2", "w-2", List.of(requirement));
        ExecutionRequest request = new ExecutionRequest(
                "e-2", assignment, new Authorization("auth-2", "w-2"), List.of(), Instant.now());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ExecutionAdmissionService().admit(request));
        assertEquals("required_guidance_not_read:gateway-sot", error.getMessage());
    }

    @Test
    void localPublicGuidanceProducesReceiptAndAllowsAdmission() {
        GuidanceRequirement requirement = new GuidanceRequirement(
                "gateway-sot",
                "/public/docs/gateway/SOT.md",
                "github:kelvinka38/metatron-institution/06_GATEWAY/SOT.md@b6426c31dbbf52e8cc3668fc551602066803d21a");
        Assignment assignment = new Assignment("a-3", "w-3", List.of(requirement));

        List<GuidanceReceipt> receipts = new PublicGuidanceService().readRequired(assignment);
        assertEquals(1, receipts.size());
        assertFalse(receipts.get(0).contentSha256().isBlank());

        ExecutionRequest request = new ExecutionRequest(
                "e-3", assignment, new Authorization("auth-3", "w-3"), receipts, Instant.now());
        assertEquals(ExecutionState.ADMITTED, new ExecutionAdmissionService().admit(request));
    }

    @Test
    void receiptCannotSubstituteDifferentCanonicalProvenance() {
        GuidanceRequirement requirement = new GuidanceRequirement(
                "gateway-sot",
                "/public/docs/gateway/SOT.md",
                "canonical:expected");
        GuidanceReceipt receipt = new GuidanceReceipt(
                "gateway-sot",
                "/public/docs/gateway/SOT.md",
                "canonical:other",
                "abc123",
                Instant.now());
        Assignment assignment = new Assignment("a-4", "w-4", List.of(requirement));
        ExecutionRequest request = new ExecutionRequest(
                "e-4", assignment, new Authorization("auth-4", "w-4"), List.of(receipt), Instant.now());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ExecutionAdmissionService().admit(request));
        assertEquals("guidance_provenance_mismatch:gateway-sot", error.getMessage());
    }
}
