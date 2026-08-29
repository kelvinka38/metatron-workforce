package com.metatron.workforce.workers.audit;

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
import java.util.function.Supplier;

/**
 * Institutional execution bridge for read-only repository audits.
 *
 * Authority and authorization are supplied by the admitted upstream caller and
 * preserved as references. This service never mints authority. One dispatch
 * creates Work, assigns it, starts a real WorkerRuntime execution, persists
 * evidence, and advances Work to COMPLETED or BLOCKED.
 */
@Service
public final class RepositoryAuditExecutionService {
    static final String WORKER_ID = "WORKER-REPOSITORY-AUDITOR";

    private final WorkService work;
    private final WorkerRuntime runtime;
    private final Supplier<Worker> workerFactory;

    @Autowired
    public RepositoryAuditExecutionService(WorkService work) {
        this(work, new WorkerRuntime(), RepositoryAuditWorker::new);
    }

    RepositoryAuditExecutionService(WorkService work, WorkerRuntime runtime, Supplier<Worker> workerFactory) {
        this.work = Objects.requireNonNull(work);
        this.runtime = Objects.requireNonNull(runtime);
        this.workerFactory = Objects.requireNonNull(workerFactory);
    }

    public ExecutionReceipt execute(String actor, String authorityReference, String authorizationReference,
                                    String organizationContextId, String repository) {
        require(actor, "actor");
        require(authorityReference, "authorityReference");
        require(authorizationReference, "authorizationReference");
        require(organizationContextId, "organizationContextId");
        require(repository, "repository");

        Instant startedAt = Instant.now();
        String executionId = UUID.randomUUID().toString();
        String objectiveRef = "objective:repository-audit:" + executionId;
        String workId = "work:repository-audit:" + executionId;
        String assignmentRef = "assignment:repository-audit:" + executionId;
        String objective = "Audit repository " + repository.trim();

        work.originate(workId, objectiveRef, organizationContextId.trim(), WORKER_ID, objective, startedAt);
        work.assign(workId, assignmentRef, Instant.now());
        work.start(workId, Instant.now());

        String runtimeEvidenceRef = "runtime-evidence:" + workId;
        WorkerResult result;
        try {
            result = runtime.execute(workerFactory.get(), workId, objective);
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
            terminal = work.block(workId, runtimeEvidenceRef, Instant.now());
        }

        return new ExecutionReceipt(executionId, actor.trim(), authorityReference.trim(), authorizationReference.trim(),
                repository.trim(), result, terminal);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }

    public record ExecutionReceipt(String executionId, String actor, String authorityReference,
                                   String authorizationReference, String repository, WorkerResult workerResult,
                                   InstitutionalWork work) {}
}
