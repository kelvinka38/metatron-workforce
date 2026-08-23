package com.metatron.workforce.runtime;

/**
 * Runtime lifecycle states.
 *
 * Technical lifecycle only. Does not redefine execution state semantics.
 */
public enum RuntimeState {
    CREATED,
    READY,
    RUNNING,
    FAILED,
    TERMINATED
}
