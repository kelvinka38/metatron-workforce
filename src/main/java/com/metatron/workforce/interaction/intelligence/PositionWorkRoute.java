package com.metatron.workforce.interaction.intelligence;

/**
 * The planned Work for an Objective addressed to a Position whose occupant owns {@link #capability()}.
 *
 * <p>A route is declared by the capability that owns the work semantics, never by the planner. The capability must
 * be self-contained: one step performs the whole addressed Objective. When an addressed Position requires several
 * routed capabilities, the first one in the Position contract's declared capability order is used.</p>
 */
public interface PositionWorkRoute {
    /** The execution capability this route plans for. */
    String capability();

    /** The single governed step for {@code objective}. */
    ExecutionWorkSpec work(String stepId, String objective);
}
