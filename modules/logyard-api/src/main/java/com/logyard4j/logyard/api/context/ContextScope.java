package com.logyard4j.logyard.api.context;

import com.logyard4j.logyard.api.event.AttributeSet;

/**
 * A thread-confined logging context scope with idempotent close.
 *
 * <p>Closing an outer scope early leaves the inner snapshot active. When the inner
 * scope closes, closed ancestors are skipped so their context cannot be restored.
 * A close from another thread has no effect; its owning thread can still close it.</p>
 */
public final class ContextScope implements AutoCloseable {
    private enum Phase { OPEN, CLOSED }
    private final Thread owner;
    private final ContextScope previous;
    private final AttributeSet values;
    private Phase phase = Phase.OPEN;

    ContextScope(Thread owner, ContextScope previous, AttributeSet values) {
        this.owner = owner;
        this.previous = previous;
        this.values = values;
    }

    /** Closes this scope on its owning thread and restores the nearest open ancestor. */
    @Override
    public void close() {
        if (Thread.currentThread() != owner || phase == Phase.CLOSED) return;
        phase = Phase.CLOSED;
        if (LogContext.activeScope() == this) LogContext.restore(previous);
    }

    AttributeSet values() {
        return values;
    }

    ContextScope previous() {
        return previous;
    }

    boolean isOpen() {
        return phase == Phase.OPEN;
    }

    void restoreAfterTask() {
        phase = Phase.CLOSED;
        LogContext.restore(previous);
    }
}
