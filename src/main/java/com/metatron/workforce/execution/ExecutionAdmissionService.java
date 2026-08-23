package com.metatron.workforce.execution;

public final class ExecutionAdmissionService {

    public ExecutionState admit(ExecutionRequest request) {

        if(request.assignment() == null) {
            throw new IllegalStateException("assignment missing");
        }

        if(request.authorization() == null) {
            throw new IllegalStateException("authorization missing");
        }

        return ExecutionState.ADMITTED;
    }
}
