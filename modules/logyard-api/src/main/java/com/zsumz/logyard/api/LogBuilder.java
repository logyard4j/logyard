package com.zsumz.logyard.api;

import java.util.function.Supplier;

/**
 * Fluent builder for one structured event at a level already selected by a logger.
 *
 * <p>Supplier values are evaluated only when the selected level is enabled.</p>
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
     * Appends one positional template argument.
     *
     * @param value argument value
     * @return this builder
     */
    LogBuilder argument(Object value);

    /**
     * Appends one lazily evaluated positional template argument.
     *
     * @param valueSupplier argument supplier
     * @return this builder
     */
    LogBuilder argument(Supplier<?> valueSupplier);

    /**
     * Adds one structured attribute.
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
     * @param valueSupplier attribute supplier
     * @return this builder
     */
    LogBuilder add(String key, Supplier<?> valueSupplier);

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
     * @param value message template
     * @param values positional arguments
     */
    void log(String value, Object... values);
}
