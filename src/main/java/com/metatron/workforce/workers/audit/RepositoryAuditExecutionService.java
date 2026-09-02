package com.metatron.workforce.workers.audit;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.runtime.WorkerRuntime;
import com.metatron.workforce.work.InstitutionalWork;
import com.metatron.workforce.work.WorkService;
import com.metatron.workforce.workers.Worker;
import com.metatron.workforce.workers.WorkerResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** Institutional execution bridge for read-only repository audits. */
@Service
public final class RepositoryAuditExecutionService {
    public static final String WORKER_ID = "WORKER-REPOSITORY-AUDITOR";

    private final WorkService work;
    private final WorkerRuntime runtime;
    private final Function<String, Worker> legacyWorkerFactory;
    private final Function<String, RepositoryAuditCognitiveWorker> cognitiveWorkerFactory;

    @Autowired
    public RepositoryAuditExecutionService(WorkService work) {
        this.work = Objects.requireNonNull(work);
        this.runtime = new WorkerRuntime();
        this.legacyWorkerFactory = null;
        this.cognitiveWorkerFactory = RepositoryAuditCognitiveWorker::new;
    }

    RepositoryAuditExecutionService(WorkService work, WorkerRuntime runtime, Function<String, Worker> workerFactory) {
        this.work = Objects.requireNonNull(work);
        this.runtime = Objects.requireNonNull(runtime);
        this.legacyWorkerFactory = Objects.requireNonNull(workerFactory);
        this.cognitiveWorkerFactory = null;
    }

    /** Compatibility constructor for deterministic tests that inject a local legacy worker. */
    RepositoryAuditExecutionService(WorkService work, WorkerRuntime runtime, Supplier<Worker> workerFactory) {
        this(work, runtime, (Function<String, Worker>) ignoredAuthorization -> workerFactory.get());
    }

    public ExecutionReceipt execute(String actor, String authorityReference, String authorizationReference,
                                    String organizationContextId, String repository) {
        return execute(actor, authorityReference, authorizationReference, organizationContextId, repository, null);
    }

    /** Backward-compatible entrypoint for callers that do not yet provide canonical Objective/WorkSpec identity. */
    public ExecutionReceipt execute(String actor, String authorityReference, String authorizationReference,
                                    String organizationContextId, String repository, String governedAssignmentReference) {
        String executionId = UUID.randomUUID().toString();
        String objectiveId = "objective:repository-audit:" + executionId;
        ExecutionWorkSpec workSpec = new ExecutionWorkSpec(
                "repository-audit-" + executionId,
                "Audit repository " + requireValue(repository, "repository"),
                repository.trim(),
                "repository.audit.read",
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY);
        return executeInternal(actor, authorityReference, authorizationReference, organizationContextId, repository,
                governedAssignmentReference, executionId, objectiveId, workSpec);
    }

    /**
     * Canonical governed entrypoint. The actual Management Objective and WorkSpec are preserved into
     * Cognitive Worker action-journal identity rather than reconstructed from runtime-local state.
     */
    public ExecutionReceipt execute(String actor, String authorityReference, String authorizationReference,
                                    String organizationContextId, String repository, String governedAssignmentReference,
                                    String objectiveId, ExecutionWorkSpec workSpec) {
        requireValue(objectiveId, "objectiveId");
        Objects.requireNonNull(workSpec, "workSpec");
        if (workSpec.consequence() != ExecutionWorkSpec.Consequence.READ_ONLY) {
            throw new SecurityException("repository audit cognitive worker only accepts READ_ONLY work");
        }
        return executeInternal(actor, authorityReference, authorizationReference, organizationContextId, repository,
                governedAssignmentReference, UUID.randomUUID().toString(), objectiveId.trim(), workSpec);
    }

    private ExecutionReceipt executeInternal(String actor, String authorityReference, String authorizationReference,
                                             String organizationContextId, String repository,
                                             String governedAssignmentReference, String executionId,
                                             String objectiveId, ExecutionWorkSpec workSpec) {
        requireValue(actor, "actor");
        requireValue(authorityReference, "authorityReference");
        requireValue(authorizationReference, "authorizationReference");
        requireValue(organizationContextId, "organizationContextId");
        requireValue(repository, "repository");

        Instant startedAt = Instant.now();
        String workId = "work:repository-audit:" + executionId;
        String assignmentRef = governedAssignmentReference == null || governedAssignmentReference.isBlank()
                ? "assignment:repository-audit:" + executionId
                : governedAssignmentReference.trim();
        String objective = workSpec.objective();

        work.originate(workId, objectiveId, organizationContextId.trim(), WORKER_ID, objective, startedAt);
        work.assign(workId, assignmentRef, Instant.now());
        work.start(workId, Instant.now());

        String runtimeEvidenceRef = "runtime-evidence:" + workId;
        WorkerResult result;
        try {
            if (cognitiveWorkerFactory != null) {
                WorkerResult cognitive = cognitiveWorkerFactory.apply(authorizationReference.trim())
                        .execute(objectiveId, workId, assignmentRef, repository.trim(), workSpec);
                result = runtime.record(workId, cognitive);
            } else {
                result = runtime.execute(legacyWorkerFactory.apply(authorizationReference.trim()), workId, objective);
            }
        } catch (Exception failure) {
            result = new WorkerResult("WorkerRuntime", "FAILED",
                    "verdict=FAILED\nreason=runtime evidence persistence failed: "
                            + failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()),
                    Instant.now());
        }

        String authorityEvidenceRef = "authority:" + authorityReference.trim();
        String authorizationEvidenceRef = "authorization:" + authorizationReference.trim();

        InstitutionalWork terminal;
        if ("PASS".equals(result.status())) {
            terminal = work.complete(workId, "repository-audit:PASS:" + repository.trim(),
                    List.of(runtimeEvidenceRef, authorityEvidenceRef, authorizationEvidenceRef), Instant.now());
        } else {
            terminal = work.block(workId, "worker-result:" + result.worker() + ":" + result.status(), Instant.now());
        }

        return new ExecutionReceipt(executionId, actor.trim(), authorityReference.trim(), authorizationReference.trim(),
                repository.trim(), result, terminal);
    }

    private static String requireValue(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }

    public record ExecutionReceipt(String executionId, String actor, String authorityReference,
                                   String authorizationReference, String repository, WorkerResult workerResult,
                                   InstitutionalWork work) {}
}
