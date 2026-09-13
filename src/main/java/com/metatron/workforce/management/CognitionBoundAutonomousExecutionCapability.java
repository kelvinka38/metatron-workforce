package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One Worker-cognition decision boundary per assigned Work step.
 *
 * <p>This is deliberately not an authorization layer and it does not call cognition before every
 * syscall/tool effect. The Worker reasons once after allocation and before the bounded capability
 * adapter executes. Concrete security remains with the capability (for Host Commander, the
 * restricted broker). The cognition response is durable provenance and cannot manufacture
 * authority.</p>
 */
public final class CognitionBoundAutonomousExecutionCapability implements AutonomousExecutionCapability {
    private final AutonomousExecutionCapability delegate;
    private final WorkerIntelligenceService intelligence;

    public CognitionBoundAutonomousExecutionCapability(AutonomousExecutionCapability delegate,
                                                       WorkerIntelligenceService intelligence) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.intelligence = Objects.requireNonNull(intelligence, "intelligence");
    }

    @Override public String capabilityRef() { return delegate.capabilityRef(); }
    @Override public String capabilityDescription() { return delegate.capabilityDescription(); }
    @Override public String authorityReference() { return delegate.authorityReference(); }
    @Override public String authorizationReference() { return delegate.authorizationReference(); }
    @Override public double minimumCapabilityLevel() { return delegate.minimumCapabilityLevel(); }
    @Override public double requiredCapacity() { return delegate.requiredCapacity(); }
    @Override public boolean supportsWorker(String workerId) { return delegate.supportsWorker(workerId); }
    @Override public boolean supportsWorker(String workerId, ExecutionWorkSpec workSpec) {
        return delegate.supportsWorker(workerId, workSpec);
    }
    @Override public boolean supportsWork(ExecutionWorkSpec workSpec) { return delegate.supportsWork(workSpec); }
    @Override public boolean requiresIndependentObservation(ExecutionWorkSpec workSpec) {
        return delegate.requiresIndependentObservation(workSpec);
    }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.allocated()) throw new SecurityException("worker cognition requires governed Work allocation");

        List<String> cognitionInputEvidence = new ArrayList<>();
        cognitionInputEvidence.add("worker-objective:" + request.objectiveId());
        cognitionInputEvidence.add("worker-assignment:" + request.assignmentReference());
        cognitionInputEvidence.add("worker-capability:" + delegate.capabilityRef());
        if (request.dispatchBound()) cognitionInputEvidence.add("worker-dispatch:" + request.dispatchReference());

        String instructions = """
                You are the canonical Worker assigned this Work step. Decide the next bounded action
                inside the already-authorized capability. Do not claim the action already happened.
                Do not invent capabilities, credentials, authority or external effects. Return a concise
                execution decision grounded in the Work objective, target and acceptance criteria.
                """;
        String context = """
                ASSIGNED WORK
                objective_id=%s
                step_id=%s
                capability=%s
                consequence=%s
                objective=%s
                target=%s
                acceptance_criteria=%s
                evidence_requirements=%s
                """.formatted(
                request.objectiveId(), request.workSpec().stepId(), delegate.capabilityRef(),
                request.workSpec().consequence(), request.workSpec().objective(), request.workSpec().target(),
                request.workSpec().acceptanceCriteria(), request.workSpec().evidenceRequirements());

        WorkerIntelligenceService.Response decision = intelligence.reason(new WorkerIntelligenceService.Request(
                request.allocatedWorkerId(),
                delegate.capabilityRef(),
                instructions,
                context,
                cognitionInputEvidence,
                request.allocatedWorkerId(),
                request.objectiveId(),
                request.assignmentReference(),
                request.workSpec().stepId(),
                request.executionAttemptId()));

        CapabilityResult result = delegate.execute(request);
        List<String> evidence = new ArrayList<>(decision.evidenceReferences());
        evidence.add("worker-cognitive-decision:" + decision.requestReference());
        evidence.addAll(result.evidenceReferences());
        return new CapabilityResult(result.success(), result.workerId(), result.assignmentReference(),
                result.workReference(), List.copyOf(evidence), result.summary());
    }
}
