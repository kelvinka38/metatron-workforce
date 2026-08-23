package com.metatron.workforce.workers;

public interface Worker {

    WorkerResult execute(WorkerContext context);

}