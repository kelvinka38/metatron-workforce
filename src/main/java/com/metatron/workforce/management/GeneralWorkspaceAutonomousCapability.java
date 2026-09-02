package com.metatron.workforce.management;

import com.metatron.workforce.action.ActionFabric;
import com.metatron.workforce.action.ActionJournal;
import com.metatron.workforce.action.CognitiveWorkerRuntime;
import com.metatron.workforce.action.GeneralCognitiveWorkerBrain;
import com.metatron.workforce.action.GeneralCognitiveWorkerBrainFactory;
import com.metatron.workforce.action.GeneralWorkspaceActionCatalog;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** General code/file/process/Git/build/test execution composed from governed Action Fabric actions. */
@Component
public final class GeneralWorkspaceAutonomousCapability implements AutonomousExecutionCapability {
    public static final String CAPABILITY = "execution.general.workspace";
    public static final String WORKER_ID = "WORKER-GENERAL-ENGINEERING";
    public static final String AUTHORITY_REFERENCE = "policy:founder-general-engineering-workspace:v1";
    public static final String AUTHORIZATION_REFERENCE = "authorization:founder-general-engineering-workspace:v1";
    private static final int MAX_COGNITIVE_CYCLES = 48;

    private final GeneralWorkspaceActionCatalog actions;
    private final GeneralCognitiveWorkerBrainFactory brains;
    private final WorkerRuntimeProfileBindingService profiles;
    private final ObjectiveWorkspaceService workspaces;

    public GeneralWorkspaceAutonomousCapability(GeneralWorkspaceActionCatalog actions,
                                                GeneralCognitiveWorkerBrainFactory brains,
                                                WorkerRuntimeProfileBindingService profiles,
                                                ObjectiveWorkspaceService workspaces) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.brains = Objects.requireNonNull(brains, "brains");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    }

    @Override public String capabilityRef() { return CAPABILITY; }
    @Override public String authorityReference() { return AUTHORITY_REFERENCE; }
    @Override public String authorizationReference() { return AUTHORIZATION_REFERENCE; }
    @Override public double minimumCapabilityLevel() { return 1.0; }
    @Override public double requiredCapacity() { return 1.0; }
    @Override public boolean supportsWorker(String workerId) { return WORKER_ID.equals(workerId); }

    @Override
    public String capabilityDescription() {
        return CAPABILITY + " — general Cognitive Worker using governed Objective workspace and isolated sandbox actions";
    }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.allocated()) throw new SecurityException("governed allocation required for general workspace execution");
        if (!WORKER_ID.equals(request.allocatedWorkerId())) throw new SecurityException("general workspace worker mismatch");
        if (!AUTHORIZATION_REFERENCE.equals(request.authorizationReference())) {
            throw new SecurityException("general workspace authorization mismatch");
        }
        WorkerRuntimeProfileBindingService.Binding binding = profiles.requireBinding(request.allocatedWorkerId());
        if (!WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE.equals(binding.profile().profileRef())) {
            throw new SecurityException("general workspace runtime profile mismatch");
        }
        if (!binding.profile().writableWorkspace()) {
            throw new SecurityException("general workspace profile is not writable");
        }

        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision(
                request.objectiveId(), request.allocatedWorkerId());
        ActionFabric fabric = new ActionFabric(actions.actions(
                request.allocatedWorkerId(), request.authorizationReference(), request.objectiveId()));
        GeneralCognitiveWorkerBrain brain = brains.create();
        CognitiveWorkerRuntime runtime = new CognitiveWorkerRuntime(
                fabric, ActionJournal.runtimeEvidenceJournal(), MAX_COGNITIVE_CYCLES);
        CognitiveWorkerRuntime.Outcome outcome = runtime.execute(
                request.allocatedWorkerId(),
                request.assignmentReference(),
                request.authorizationReference(),
                request.objectiveId(),
                request.workSpec(),
                request.idempotencyKey(),
                brain);

        List<String> evidence = new ArrayList<>(outcome.evidenceReferences());
        evidence.addAll(brain.evidenceReferences());
        evidence.add("general-action-composition:capability=" + request.workSpec().requiredCapability()
                + ":workspace=" + workspace.workspaceRef()
                + ":profile=" + binding.profile().profileRef());
        evidence.add("general-action-catalog:" + binding.profile().actionRefs().stream().sorted().toList());
        return new CapabilityResult(
                outcome.success(),
                request.allocatedWorkerId(),
                request.assignmentReference(),
                workspace.workspaceRef(),
                evidence,
                outcome.summary());
    }
}
