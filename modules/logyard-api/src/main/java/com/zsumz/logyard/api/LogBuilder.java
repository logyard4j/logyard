package com.zsumz.logyard.api;

import com.zsumz.logyard.api.event.AttributeSet;

import java.util.function.Supplier;

/**
 * Fluent builder for one structured event at a level already selected by a logger.
 *
 * <p>Lazy values use the distinct {@link #argumentLazy(Supplier)} and
 * {@link #addLazy(String, Supplier)} methods, so a literal {@code null} value always
 * selects the eager overload and is captured as a null value. Suppliers are evaluated
 * only when an enabled event enters the protected publication boundary.</p>
 *
 * <p>A builder is mutable, thread-confined, and logically single-use: invoke one
 * {@code log} method, then discard it. Do not share a builder between threads or
 * retain it for another event.</p>
 */
public interface LogBuilder {
    /**
     * Sets the stable event name.
     *
     * @param value event name
     * @return this builder
     */
    LogBuilder event(String value);

    /**
     * Sets the message template rendered when the event reaches an output.
     *
     * @param value message template
     * @return this builder
     */
    LogBuilder message(String value);

    /**
     * Appends one positional template argument; a {@code null} value is captured as null.
     *
     * @param value argument value
     * @return this builder
     */
    LogBuilder argument(Object value);

    /**
     * Appends one lazily evaluated positional template argument.
     *
     * @param valueSupplier argument supplier, required when this builder is enabled
     * @return this builder
     */
    LogBuilder argumentLazy(Supplier<?> valueSupplier);

    /**
     * Adds one structured attribute; a {@code null} value is captured as null.
     *
     * @param key attribute key
     * @param value attribute value
     * @return this builder
     */
    LogBuilder add(String key, Object value);

    /**
     * Adds one lazily evaluated structured attribute.
     *
     * @param key attribute key
     * @param valueSupplier attribute supplier, required when this builder is enabled
     * @return this builder
     */
    LogBuilder addLazy(String key, Supplier<?> valueSupplier);

    /**
     * Adds every attribute of a prebuilt set, replacing keys already added.
     *
     * @param values attributes to add, required when this builder is enabled
     * @return this builder
     */
    LogBuilder addAll(AttributeSet values);

    /**
     * Attaches a throwable to the event.
     *
     * @param value throwable to capture
     * @return this builder
     */
    LogBuilder cause(Throwable value);

    /** Publishes the event using the configured event name and message template. */
    void log();

    /**
     * Publishes the event with a replacement message template.
     *
     * @param value message template
     */
    void log(String value);

    /**
     * Publishes the event with a replacement message template and positional arguments.
     *
     * <p>Unlike the level-named convenience methods, a trailing {@link Throwable} stays
     * a positional argument here; use {@link #cause(Throwable)} to capture it.</p>
     *
     * @param value message template
     * @param values positional arguments
     */
    void log(String value, Object... values);
}
