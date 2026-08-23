package com.metatron.workforce.execution;

public final class ExecutionRecoveryService {

    private final ExecutionRepository repository;

    public ExecutionRecoveryService(
            ExecutionRepository repository
    ){
        this.repository = repository;
    }

    public ExecutionPersistenceRecord recover(
            String executionId
    ){
        return repository.load(executionId);
    }
}
