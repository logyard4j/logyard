package com.logyard4j.api.ingress;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.AttributeSet;

/**
 * Narrow adapter-facing ingress contract for publishing normalized events.
 *
 * <p>Compatibility adapters can depend on this interface without coupling themselves to the
 * native logger's convenience and builder APIs.</p>
 */
public interface LogEventIngress {
    /**
     * Returns the stable hierarchical logger name.
     *
     * @return logger name
     */
    String name();

    /**
     * Returns whether events at a level can reach at least one configured route.
     *
     * @param level level to test
     * @return {@code true} when the level is enabled
     */
    boolean isEnabled(Level level);

    /**
     * Publishes a normalized adapter event while preserving its source representation.
     *
     * @param level event level
     * @param eventName stable event name, or {@code null}
     * @param messageTemplate message template, or {@code null}
     * @param arguments positional arguments
     * @param attributes structured attributes
     * @param throwable throwable to capture, or {@code null}
     */
    void log(
            Level level,
            String eventName,
            String messageTemplate,
            Object[] arguments,
            AttributeSet attributes,
            Throwable throwable);

    /**
     * Publishes a normalized adapter event with source timestamp and logical thread metadata.
     *
     * @param level event level
     * @param eventName stable event name, or {@code null}
     * @param messageTemplate message template, or {@code null}
     * @param arguments positional arguments
     * @param attributes structured attributes
     * @param throwable throwable to capture, or {@code null}
     * @param metadata source metadata supplied by the adapter
     */
    void log(
            Level level,
            String eventName,
            String messageTemplate,
            Object[] arguments,
            AttributeSet attributes,
            Throwable throwable,
            IngressMetadata metadata);
}
