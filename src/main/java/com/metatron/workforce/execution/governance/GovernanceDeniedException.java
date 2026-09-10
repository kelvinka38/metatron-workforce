package com.metatron.workforce.execution.governance;

/** Fail-closed typed denial; callers must not reinterpret it as a generic retry signal. */
public final class GovernanceDeniedException extends SecurityException {
    private final String code;

    public GovernanceDeniedException(String code, String message) {
        super(code + ":" + message);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("governance denial code required");
        this.code = code.trim();
    }

    public String code() { return code; }
}
