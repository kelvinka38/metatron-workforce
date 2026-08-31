package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.workers.audit.RepositoryAuditExecutionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pre-authorized Founder read-only repository audit exposed to autonomous management. */
@Component
public final class RepositoryAuditAutonomousCapability implements AutonomousExecutionCapability {
    public static final String CAPABILITY = "repository.audit.read";
    static final String AUTHORITY_REFERENCE = "policy:founder-readonly-repository-audit:v1";
    static final String AUTHORIZATION_REFERENCE = "authorization:founder-readonly-repository-audit:v1";

    private final RepositoryAuditExecutionService repositoryAudit;

    public RepositoryAuditAutonomousCapability(RepositoryAuditExecutionService repositoryAudit) {
        this.repositoryAudit = Objects.requireNonNull(repositoryAudit, "repositoryAudit");
    }

    @Override public String capabilityRef() { return CAPABILITY; }
    @Override public String authorityReference() { return AUTHORITY_REFERENCE; }
    @Override public String authorizationReference() { return AUTHORIZATION_REFERENCE; }
    @Override public boolean supportsWorker(String workerId) {
        return RepositoryAuditExecutionService.WORKER_ID.equals(workerId);
    }

    @Override public String capabilityDescription() {
        return CAPABILITY + " — governed read-only GitHub repository audit; target must be owner/repo";
    }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        if (!request.allocated()) {
            throw new SecurityException("governed allocation required before repository audit effect");
        }
        if (request.workSpec().consequence() != ExecutionWorkSpec.Consequence.READ_ONLY) {
            throw new SecurityException("repository.audit.read cannot execute MUTATING work");
        }
        if (!AUTHORIZATION_REFERENCE.equals(request.authorizationReference())) {
            throw new SecurityException("repository audit authorization mismatch");
        }
        if (!RepositoryAuditExecutionService.WORKER_ID.equals(request.allocatedWorkerId())) {
            throw new SecurityException("repository audit worker mismatch");
        }

        String repository = requireRepositoryTarget(request.workSpec().target());
        RepositoryAuditExecutionService.ExecutionReceipt receipt = repositoryAudit.execute(
                request.humanId(),
                AUTHORITY_REFERENCE,
                request.authorizationReference(),
                request.organizationContextId(),
                repository,
                request.assignmentReference());
        boolean success = "COMPLETED".equals(receipt.work().status().name())
                && "PASS".equals(receipt.workerResult().status());
        if (!request.assignmentReference().equals(receipt.work().assignmentRef())) {
            throw new IllegalStateException("institutional work lost governed assignment identity");
        }
        List<String> evidence = new ArrayList<>(receipt.work().evidenceRefs());
        evidence.add("worker-result:" + receipt.workerResult().worker() + ":" + receipt.workerResult().status());
        evidence.add("work:" + receipt.work().workId());
        return new CapabilityResult(
                success,
                request.allocatedWorkerId(),
                request.assignmentReference(),
                receipt.work().workId(),
                evidence,
                success ? "repository audit completed: " + repository
                        : "repository audit failed: " + repository + " status=" + receipt.workerResult().status());
    }

    private static String requireRepositoryTarget(String target) {
        if (target == null) throw new IllegalArgumentException("repository target required");
        String value = target.trim();
        if (value.startsWith("https://github.com/")) value = value.substring("https://github.com/".length());
        value = value.replaceAll("\\.git$", "");
        if (!value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("repository target must be owner/repo");
        }
        return value;
    }
}
