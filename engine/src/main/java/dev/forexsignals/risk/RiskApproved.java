package dev.forexsignals.risk;

/**
 * A signal the risk gate approved. Only {@link RiskGate} can create one (package-private constructor), and the tracker
 * only accepts this type, so no strategy can bypass the gate (PLAN §9).
 */
public final class RiskApproved<T> {
    private final T value;
    private final boolean deliver;

    RiskApproved(T value, boolean deliver) {
        this.value = value;
        this.deliver = deliver;
    }

    public T value() { return value; }

    /** False in SHADOW mode: journaled and tracked, never delivered (STRATEGY_NOT_LIVE). */
    public boolean deliver() { return deliver; }
}
