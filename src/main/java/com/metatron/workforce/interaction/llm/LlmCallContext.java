package com.metatron.workforce.interaction.llm;

import java.util.Objects;

/**
 * Thread-confined correlation/context supplied by the institutional ingress before any provider call.
 * It is transport metadata and context only; it grants no authority or authorization.
 */
public final class LlmCallContext {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private LlmCallContext() {}

    public static Scope open(String logicalRequestRef, String systemContextPrefix,
                             FrontierCallBudget defaultBudget) {
        if (CURRENT.get() != null) throw new IllegalStateException("llm_call_context_already_open");
        String logical = logicalRequestRef == null ? "" : logicalRequestRef.trim();
        if (logical.isBlank()) throw new IllegalArgumentException("logicalRequestRef must not be blank");
        State state = new State(logical,
                systemContextPrefix == null ? "" : systemContextPrefix.trim(),
                Objects.requireNonNull(defaultBudget, "defaultBudget"));
        CURRENT.set(state);
        return new Scope(state);
    }

    public static String logicalRequestRef() {
        State state = CURRENT.get();
        return state == null ? "" : state.logicalRequestRef;
    }

    public static String systemContextPrefix() {
        State state = CURRENT.get();
        return state == null ? "" : state.systemContextPrefix;
    }

    public static FrontierCallBudget defaultBudget() {
        State state = CURRENT.get();
        return state == null ? null : state.defaultBudget;
    }

    private record State(String logicalRequestRef, String systemContextPrefix,
                         FrontierCallBudget defaultBudget) {}

    public static final class Scope implements AutoCloseable {
        private final State state;
        private boolean closed;

        private Scope(State state) {
            this.state = state;
        }

        @Override
        public void close() {
            if (closed) return;
            if (CURRENT.get() == state) CURRENT.remove();
            closed = true;
        }
    }
}
