package com.logyard4j.logyard.api.context;

import com.logyard4j.logyard.api.event.AttributeSet;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Thread-confined, immutable attributes attached to events captured in an open scope.
 *
 * <p>Nested pushes merge once when opened. Event attributes override scoped attributes.
 * Context is not inherited by new threads; use a wrapped task for explicit propagation.</p>
 */
public final class LogContext {
    private static final ThreadLocal<ContextScope> CURRENT = new ThreadLocal<>();

    private LogContext() {
    }

    /** Returns the current snapshot, or an empty set when no scope is open.
     * @return caller-thread attributes
     */
    public static AttributeSet current() {
        ContextScope scope = CURRENT.get();
        return scope == null ? AttributeSet.EMPTY : scope.values();
    }

    /** Opens a scope with additional attributes replacing matching outer keys.
     * @param values detached attributes to attach
     * @return scope restoring the nearest still-open parent on close
     */
    public static ContextScope push(AttributeSet values) {
        Objects.requireNonNull(values, "values");
        AttributeSet previous = current();
        return bind(previous.mergedWith(values));
    }

    /** Opens a scope with one additional attribute.
     * @param key nonblank, nonreserved attribute key
     * @param value value to capture
     * @return scope restoring the previous context
     */
    public static ContextScope push(String key, Object value) {
        return push(AttributeSet.of(key, value));
    }

    /** Captures this thread's snapshot for a task, restoring its worker's context afterwards.
     * @param task task to wrap
     * @return task with explicit context propagation, including an empty captured context
     */
    public static Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task");
        AttributeSet captured = current();
        return () -> {
            ContextScope installed = bind(captured);
            try {
                task.run();
            } finally {
                installed.restoreAfterTask();
            }
        };
    }

    /** Captures this thread's snapshot for a value-returning task.
     * @param <T> result type
     * @param task task to wrap
     * @return task restoring the worker's original context even on failure
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        AttributeSet captured = current();
        return () -> {
            ContextScope installed = bind(captured);
            try {
                return task.call();
            } finally {
                installed.restoreAfterTask();
            }
        };
    }

    static ContextScope activeScope() {
        return CURRENT.get();
    }

    static void restore(ContextScope scope) {
        while (scope != null && !scope.isOpen()) scope = scope.previous();
        // Clear the captured object graph while retaining the warmed thread-local slot.
        CURRENT.set(scope);
    }

    private static ContextScope bind(AttributeSet values) {
        ContextScope scope = new ContextScope(Thread.currentThread(), CURRENT.get(), values);
        CURRENT.set(scope);
        return scope;
    }
}
