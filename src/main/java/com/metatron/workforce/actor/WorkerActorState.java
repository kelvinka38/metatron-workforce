package com.metatron.workforce.actor;

/** Durable operational state of one canonical Worker actor. */
public enum WorkerActorState {
    IDLE,
    READY,
    WORKING,
    WAITING,
    BLOCKED,
    PAUSED,
    RECOVERING,
    OFFLINE
}
