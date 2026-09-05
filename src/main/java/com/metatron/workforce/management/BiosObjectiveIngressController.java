package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.DeterministicCapability;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepth;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.workplace.WorkplaceContinuityService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/** Internal Product boundary for durable BIOS Product #1 Objective Contracts. */
@RestController
@RequestMapping("/workforce/bios")
public final class BiosObjectiveIngressController {
    private final ManagementAutonomyService management;
    private final AutonomousManagementRunner runner;
    private final WorkplaceContinuityService workplace;

    public BiosObjectiveIngressController(ManagementAutonomyService management,
                                          AutonomousManagementRunner runner,
                                          WorkplaceContinuityService workplace) {
        this.management = management;
        this.runner = runner;
        this.workplace = workplace;
    }

    @PostMapping("/objectives")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BiosHandoffReceipt accept(@RequestHeader("X-Metatron-Actor") String actor,
                                     @RequestBody BiosObjectiveCommand command) {
        require(command.objectiveId(), "objectiveId");
        require(command.caseId(), "caseId");
        require(command.programId(), "programId");
        require(command.initiatingActorId(), "initiatingActorId");
        require(command.organizationContextId(), "organizationContextId");
        require(command.desiredOutcome(), "desiredOutcome");
        if (actor == null || !actor.equals(command.initiatingActorId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "caller attribution mismatch");
        }
        if (command.acceptanceCriteria() == null || command.acceptanceCriteria().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "acceptanceCriteria required");
        }
        if (command.evidenceRequirements() == null || command.evidenceRequirements().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "evidenceRequirements required");
        }

        String requestedOutput = "Evidence-backed terminal Objective outcome"
                + "\nAcceptance criteria: " + String.join("; ", command.acceptanceCriteria())
                + "\nEvidence requirements: " + String.join("; ", command.evidenceRequirements());
        NormalizedRequest request = new NormalizedRequest(
                command.desiredOutcome(), command.target() == null ? "" : command.target(),
                command.constraints() == null ? List.of() : command.constraints(), IntelligenceDepth.ANALYZE,
                requestedOutput, List.of(),
                command.prohibitions() == null ? List.of() : command.prohibitions(), "", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE, List.of(),
                DeterministicCapability.NONE, List.of(), List.of(), false, null, LlmProvider.OPENAI, "");

        Instant now = Instant.now();
        String admission = "bios-objective-admission:" + command.initiatingActorId() + ":metatron-workforce";
        ManagementObjective objective;
        try {
            objective = management.get(command.objectiveId());
            AutonomousObjectiveWork existing = management.findAutonomousWork(command.objectiveId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "objective id has no durable autonomous work context"));
            if (!existing.humanId().equals(command.initiatingActorId())
                    || !objective.organizationContextId().equals(command.organizationContextId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "objective id belongs to another institutional context");
            }
        } catch (IllegalArgumentException unknown) {
            objective = management.acceptHumanObjective(
                    command.objectiveId(), "metatron-workforce", command.organizationContextId(),
                    render(command), "human:" + command.initiatingActorId(), admission,
                    command.caseId(), "bios:case:" + command.caseId(),
                    "bios:objective:" + command.objectiveId(), "bios", request, now);
            workplace.bindAcceptedObjective(command.objectiveId(), command.initiatingActorId(),
                    "bios:case:" + command.caseId(), "bios", "bios:objective:" + command.objectiveId(),
                    admission, now);
        }
        runner.wake();
        return new BiosHandoffReceipt(true, objective.objectiveId(), objective.ownerWorkerId(),
                objective.status().name(), "OBJECTIVE_ACCEPTED_FOR_AUTONOMOUS_MANAGEMENT");
    }

    private static String render(BiosObjectiveCommand command) {
        return command.desiredOutcome() + "\nBIOS Case: " + command.caseId()
                + "\nBIOS Program: " + command.programId()
                + "\nAcceptance: " + String.join("; ", command.acceptanceCriteria())
                + "\nEvidence: " + String.join("; ", command.evidenceRequirements());
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " required");
        }
    }

    public record BiosObjectiveCommand(String objectiveId, String caseId, String programId,
            String initiatingActorId, String organizationContextId, String desiredOutcome, String target,
            List<String> constraints, List<String> prohibitions, List<String> acceptanceCriteria,
            List<String> evidenceRequirements) {}

    public record BiosHandoffReceipt(boolean accepted, String objectiveId, String ownerWorker,
                                     String objectiveStatus, String reason) {}
}
