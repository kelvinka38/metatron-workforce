package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable operational safety gate for autonomous Objectives.
 * It enforces delegated ceilings but does not create authority or authoritative accounting truth.
 */
public final class AutonomySafetyService {
    public static final double DEFAULT_MAX_COST_UNITS = 100.0;
    public static final int DEFAULT_MAX_DISPATCH_ATTEMPTS = 32;
    public static final Duration DEFAULT_MAX_DURATION = Duration.ofHours(1);
    public static final AutonomySafetyState.RiskLevel DEFAULT_MAX_RISK = AutonomySafetyState.RiskLevel.HIGH;

    private final Map<String, AutonomySafetyState> states = new LinkedHashMap<>();
    private final AutonomySafetyStateStore store;
    private final Clock clock;
    private final double defaultMaxCostUnits;
    private final int defaultMaxDispatchAttempts;
    private final Duration defaultMaxDuration;
    private final AutonomySafetyState.RiskLevel defaultMaxRisk;

    public AutonomySafetyService() {
        this(new InMemoryAutonomySafetyStateStore(), Clock.systemUTC(), DEFAULT_MAX_COST_UNITS,
                DEFAULT_MAX_DISPATCH_ATTEMPTS, DEFAULT_MAX_DURATION, DEFAULT_MAX_RISK);
    }

    public AutonomySafetyService(AutonomySafetyStateStore store, Clock clock,
                                 double defaultMaxCostUnits, int defaultMaxDispatchAttempts,
                                 Duration defaultMaxDuration, AutonomySafetyState.RiskLevel defaultMaxRisk) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (defaultMaxCostUnits <= 0 || !Double.isFinite(defaultMaxCostUnits))
            throw new IllegalArgumentException("defaultMaxCostUnits must be finite and positive");
        if (defaultMaxDispatchAttempts < 1) throw new IllegalArgumentException("defaultMaxDispatchAttempts must be positive");
        if (defaultMaxDuration.isZero() || defaultMaxDuration.isNegative())
            throw new IllegalArgumentException("defaultMaxDuration must be positive");
        this.defaultMaxCostUnits = defaultMaxCostUnits;
        this.defaultMaxDispatchAttempts = defaultMaxDispatchAttempts;
        this.defaultMaxDuration = defaultMaxDuration;
        this.defaultMaxRisk = Objects.requireNonNull(defaultMaxRisk, "defaultMaxRisk");
        states.putAll(store.load().objectives());
    }

    public synchronized AutonomySafetyState ensureObjective(String objectiveId) {
        return ensureObjective(objectiveId, clock.instant());
    }

    public synchronized AutonomySafetyState ensureObjective(String objectiveId, Instant at) {
        requireText(objectiveId, "objectiveId");
        Objects.requireNonNull(at, "at");
        AutonomySafetyState existing = states.get(objectiveId);
        if (existing != null) return existing;
        AutonomySafetyState created = new AutonomySafetyState(objectiveId,
                AutonomySafetyState.ControlStatus.RUNNING, false, "", defaultMaxCostUnits, 0.0,
                defaultMaxDispatchAttempts, 0, at.plus(defaultMaxDuration), defaultMaxRisk,
                Map.of(), java.util.List.of(), 1, at, at);
        states.put(objectiveId, created);
        persist();
        return created;
    }

    public synchronized AutonomySafetyState configureEnvelope(String objectiveId, double maxCostUnits,
            int maxDispatchAttempts, Instant deadline, AutonomySafetyState.RiskLevel maxRisk,
            String authorityReference, Instant at) {
        requireText(authorityReference, "authorityReference");
        AutonomySafetyState current = ensureObjective(objectiveId, at);
        if (maxCostUnits <= 0 || !Double.isFinite(maxCostUnits)) throw new IllegalArgumentException("maxCostUnits must be finite and positive");
        if (maxCostUnits < current.consumedCostUnits()) throw new IllegalArgumentException("maxCostUnits below already consumed cost");
        if (maxDispatchAttempts < current.consumedDispatchAttempts()) throw new IllegalArgumentException("maxDispatchAttempts below already consumed attempts");
        if (maxDispatchAttempts < 1) throw new IllegalArgumentException("maxDispatchAttempts must be positive");
        if (deadline == null || !deadline.isAfter(at)) throw new IllegalArgumentException("deadline must be in the future");
        AutonomySafetyState updated = copy(current, current.controlStatus(), current.authorityRevoked(),
                current.revokedAuthorityReference(), maxCostUnits, current.consumedCostUnits(),
                maxDispatchAttempts, current.consumedDispatchAttempts(), deadline,
                Objects.requireNonNull(maxRisk, "maxRisk"), current.dispatchCharges(), current.amendments(), at);
        states.put(objectiveId, updated); persist(); return updated;
    }

    public synchronized AutonomySafetyState pause(String objectiveId, String authorityReference, Instant at) {
        requireText(authorityReference, "authorityReference");
        AutonomySafetyState current = ensureObjective(objectiveId, at);
        if (current.controlStatus() == AutonomySafetyState.ControlStatus.CANCELLED)
            throw new IllegalStateException("objective-control-cancelled");
        return save(copy(current, AutonomySafetyState.ControlStatus.PAUSED, current.authorityRevoked(),
                current.revokedAuthorityReference(), current.maxCostUnits(), current.consumedCostUnits(),
                current.maxDispatchAttempts(), current.consumedDispatchAttempts(), current.deadline(),
                current.maxRisk(), current.dispatchCharges(), current.amendments(), at));
    }

    public synchronized AutonomySafetyState resume(String objectiveId, String authorityReference, Instant at) {
        requireText(authorityReference, "authorityReference");
        AutonomySafetyState current = ensureObjective(objectiveId, at);
        if (current.controlStatus() == AutonomySafetyState.ControlStatus.CANCELLED)
            throw new IllegalStateException("objective-control-cancelled");
        return save(copy(current, AutonomySafetyState.ControlStatus.RUNNING, current.authorityRevoked(),
                current.revokedAuthorityReference(), current.maxCostUnits(), current.consumedCostUnits(),
                current.maxDispatchAttempts(), current.consumedDispatchAttempts(), current.deadline(),
                current.maxRisk(), current.dispatchCharges(), current.amendments(), at));
    }

    public synchronized AutonomySafetyState cancel(String objectiveId, String authorityReference, Instant at) {
        requireText(authorityReference, "authorityReference");
        AutonomySafetyState current = ensureObjective(objectiveId, at);
        return save(copy(current, AutonomySafetyState.ControlStatus.CANCELLED, current.authorityRevoked(),
                current.revokedAuthorityReference(), current.maxCostUnits(), current.consumedCostUnits(),
                current.maxDispatchAttempts(), current.consumedDispatchAttempts(), current.deadline(),
                current.maxRisk(), current.dispatchCharges(), current.amendments(), at));
    }

    public synchronized AutonomySafetyState revokeAuthority(String objectiveId, String authorityReference, Instant at) {
        requireText(authorityReference, "authorityReference");
        AutonomySafetyState current = ensureObjective(objectiveId, at);
        return save(copy(current, current.controlStatus(), true, authorityReference,
                current.maxCostUnits(), current.consumedCostUnits(), current.maxDispatchAttempts(),
                current.consumedDispatchAttempts(), current.deadline(), current.maxRisk(),
                current.dispatchCharges(), current.amendments(), at));
    }

    public synchronized AutonomySafetyState restoreAuthority(String objectiveId, String authorityReference, Instant at) {
        requireText(authorityReference, "authorityReference");
        AutonomySafetyState current = ensureObjective(objectiveId, at);
        return save(copy(current, current.controlStatus(), false, "",
                current.maxCostUnits(), current.consumedCostUnits(), current.maxDispatchAttempts(),
                current.consumedDispatchAttempts(), current.deadline(), current.maxRisk(),
                current.dispatchCharges(), current.amendments(), at));
    }

    public synchronized AutonomySafetyState amend(String objectiveId, String amendmentReference, String detail,
                                                   String authorityReference, Instant at) {
        requireText(amendmentReference, "amendmentReference");
        requireText(detail, "detail");
        requireText(authorityReference, "authorityReference");
        AutonomySafetyState current = ensureObjective(objectiveId, at);
        if (current.controlStatus() == AutonomySafetyState.ControlStatus.CANCELLED)
            throw new IllegalStateException("objective-control-cancelled");
        var amendments = new ArrayList<>(current.amendments());
        amendments.add(new AutonomySafetyState.Amendment(amendmentReference, detail, authorityReference, at));
        return save(copy(current, AutonomySafetyState.ControlStatus.PAUSED, current.authorityRevoked(),
                current.revokedAuthorityReference(), current.maxCostUnits(), current.consumedCostUnits(),
                current.maxDispatchAttempts(), current.consumedDispatchAttempts(), current.deadline(),
                current.maxRisk(), current.dispatchCharges(), amendments, at));
    }

    /** Atomically enforces and charges one durable dispatch attempt. Duplicate dispatch delivery is not re-charged. */
    public synchronized DispatchBudgetReceipt reserveDispatch(String objectiveId, String dispatchReference,
            int attempt, String authorityReference, ExecutionWorkSpec.Consequence consequence,
            double requiredCapacity, Instant at) {
        requireText(dispatchReference, "dispatchReference");
        requireText(authorityReference, "authorityReference");
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        if (requiredCapacity <= 0 || !Double.isFinite(requiredCapacity))
            throw new IllegalArgumentException("requiredCapacity must be finite and positive");
        Objects.requireNonNull(consequence, "consequence");
        AutonomySafetyState current = ensureObjective(objectiveId, at);
        AutonomySafetyState.DispatchCharge existing = current.dispatchCharges().get(dispatchReference);
        if (existing != null) return receipt(current, existing, true);

        enforceControl(current, authorityReference, consequence, at);
        double costUnits = requiredCapacity * (consequence == ExecutionWorkSpec.Consequence.READ_ONLY ? 1.0 : 5.0);
        if (current.consumedDispatchAttempts() + 1 > current.maxDispatchAttempts())
            throw new SafetyGateException("attempt-ceiling-exceeded", current);
        if (current.consumedCostUnits() + costUnits > current.maxCostUnits() + 1e-9)
            throw new SafetyGateException("budget-threshold-exceeded", current);

        Map<String, AutonomySafetyState.DispatchCharge> charges = new LinkedHashMap<>(current.dispatchCharges());
        AutonomySafetyState.DispatchCharge charge = new AutonomySafetyState.DispatchCharge(
                dispatchReference, costUnits, attempt, at);
        charges.put(dispatchReference, charge);
        AutonomySafetyState updated = copy(current, current.controlStatus(), current.authorityRevoked(),
                current.revokedAuthorityReference(), current.maxCostUnits(), current.consumedCostUnits() + costUnits,
                current.maxDispatchAttempts(), current.consumedDispatchAttempts() + 1, current.deadline(),
                current.maxRisk(), charges, current.amendments(), at);
        states.put(objectiveId, updated); persist();
        return receipt(updated, charge, false);
    }

    public synchronized void assertProgressAllowed(String objectiveId, String authorityReference,
                                                   ExecutionWorkSpec.Consequence consequence, Instant at) {
        AutonomySafetyState current = ensureObjective(objectiveId, at);
        enforceControl(current, authorityReference, consequence, at);
    }

    public synchronized Optional<AutonomySafetyState> find(String objectiveId) {
        return Optional.ofNullable(states.get(objectiveId));
    }

    public synchronized AutonomySafetyState get(String objectiveId) {
        AutonomySafetyState state = states.get(objectiveId);
        if (state == null) throw new IllegalArgumentException("unknown autonomy safety state: " + objectiveId);
        return state;
    }

    private void enforceControl(AutonomySafetyState state, String authorityReference,
                                ExecutionWorkSpec.Consequence consequence, Instant at) {
        if (state.controlStatus() == AutonomySafetyState.ControlStatus.PAUSED)
            throw new SafetyGateException("objective-paused", state);
        if (state.controlStatus() == AutonomySafetyState.ControlStatus.CANCELLED)
            throw new SafetyGateException("objective-cancelled", state);
        if (state.authorityRevoked())
            throw new SafetyGateException("authority-revoked:" + state.revokedAuthorityReference(), state);
        if (!at.isBefore(state.deadline()))
            throw new SafetyGateException("deadline-exceeded", state);
        AutonomySafetyState.RiskLevel actualRisk = consequence == ExecutionWorkSpec.Consequence.READ_ONLY
                ? AutonomySafetyState.RiskLevel.LOW : AutonomySafetyState.RiskLevel.HIGH;
        if (actualRisk.ordinal() > state.maxRisk().ordinal())
            throw new SafetyGateException("risk-threshold-exceeded:" + actualRisk, state);
        requireText(authorityReference, "authorityReference");
    }

    private AutonomySafetyState save(AutonomySafetyState state) {
        states.put(state.objectiveId(), state); persist(); return state;
    }

    private void persist() { store.save(new AutonomySafetyStateStore.Snapshot(states)); }

    private static DispatchBudgetReceipt receipt(AutonomySafetyState state,
            AutonomySafetyState.DispatchCharge charge, boolean replay) {
        return new DispatchBudgetReceipt(state.objectiveId(), charge.dispatchReference(), charge.costUnits(),
                state.consumedCostUnits(), state.maxCostUnits(), state.consumedDispatchAttempts(),
                state.maxDispatchAttempts(), state.deadline(), state.maxRisk(), replay, state.version());
    }

    private static AutonomySafetyState copy(AutonomySafetyState current,
            AutonomySafetyState.ControlStatus controlStatus, boolean authorityRevoked,
            String revokedAuthorityReference, double maxCostUnits, double consumedCostUnits,
            int maxDispatchAttempts, int consumedDispatchAttempts, Instant deadline,
            AutonomySafetyState.RiskLevel maxRisk, Map<String, AutonomySafetyState.DispatchCharge> charges,
            java.util.List<AutonomySafetyState.Amendment> amendments, Instant at) {
        return new AutonomySafetyState(current.objectiveId(), controlStatus, authorityRevoked,
                revokedAuthorityReference, maxCostUnits, consumedCostUnits, maxDispatchAttempts,
                consumedDispatchAttempts, deadline, maxRisk, charges, amendments,
                current.version() + 1, current.createdAt(), at);
    }

    public record DispatchBudgetReceipt(String objectiveId, String dispatchReference, double chargedCostUnits,
            double consumedCostUnits, double maxCostUnits, int consumedDispatchAttempts,
            int maxDispatchAttempts, Instant deadline, AutonomySafetyState.RiskLevel maxRisk,
            boolean replay, long safetyVersion) {}

    public static final class SafetyGateException extends IllegalStateException {
        private final String reason;
        private final AutonomySafetyState state;
        SafetyGateException(String reason, AutonomySafetyState state) {
            super("autonomy-safety-gate:" + reason);
            this.reason = reason; this.state = state;
        }
        public String reason() { return reason; }
        public AutonomySafetyState state() { return state; }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
