package com.metatron.workforce.execution;

public interface ExecutionRepository {

    void save(ExecutionPersistenceRecord record);

    ExecutionPersistenceRecord load(String executionId);
}
