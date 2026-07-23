package com.zsumz.logyard.api;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.ingress.IngressMetadata;

/**
 * Stable native logger contract.
 *
 * <p>Implementations must perform level checks before inspecting arguments, suppliers, context,
 * clocks, or thread metadata.</p>
 */
public interface LogyardLogger {
    /**
     * Returns this logger's stable hierarchical name.
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
     * Returns whether trace events are enabled.
     *
     * @return {@code true} when trace is enabled
     */
    boolean isTraceEnabled();

    /**
     * Returns whether debug events are enabled.
     *
     * @return {@code true} when debug is enabled
     */
    boolean isDebugEnabled();

    /**
     * Returns whether informational events are enabled.
     *
     * @return {@code true} when info is enabled
     */
    boolean isInfoEnabled();

    /**
     * Returns whether warning events are enabled.
     *
     * @return {@code true} when warn is enabled
     */
    boolean isWarnEnabled();

    /**
     * Returns whether error events are enabled.
     *
     * @return {@code true} when error is enabled
     */
    boolean isErrorEnabled();

    /**
     * Starts a structured event at the supplied level.
     *
     * @param level event level
     * @return event builder
     */
    LogBuilder at(Level level);

    /**
     * Starts a structured trace event.
     *
     * @return event builder
     */
    LogBuilder atTrace();

    /**
     * Starts a structured debug event.
     *
     * @return event builder
     */
    LogBuilder atDebug();

    /**
     * Starts a structured informational event.
     *
     * @return event builder
     */
    LogBuilder atInfo();

    /**
     * Starts a structured warning event.
     *
     * @return event builder
     */
    LogBuilder atWarn();

    /**
     * Starts a structured error event.
     *
     * @return event builder
     */
    LogBuilder atError();

    /**
     * Logs a trace message.
     *
     * @param message message template
     */
    void trace(String message);

    /**
     * Logs a trace message with one positional argument.
     *
     * @param message message template
     * @param argument positional argument
     */
    void trace(String message, Object argument);

    /**
     * Logs a trace message with two positional arguments.
     *
     * @param message message template
     * @param first first positional argument
     * @param second second positional argument
     */
    void trace(String message, Object first, Object second);

    /**
     * Logs a trace message with positional arguments.
     *
     * @param message message template
     * @param arguments positional arguments
     */
    void trace(String message, Object... arguments);

    /**
     * Logs a trace message with a throwable.
     *
     * @param message message template
     * @param error throwable to capture
     */
    void trace(String message, Throwable error);

    /**
     * Logs a debug message.
     *
     * @param message message template
     */
    void debug(String message);

    /**
     * Logs a debug message with one positional argument.
     *
     * @param message message template
     * @param argument positional argument
     */
    void debug(String message, Object argument);

    /**
     * Logs a debug message with two positional arguments.
     *
     * @param message message template
     * @param first first positional argument
     * @param second second positional argument
     */
    void debug(String message, Object first, Object second);

    /**
     * Logs a debug message with positional arguments.
     *
     * @param message message template
     * @param arguments positional arguments
     */
    void debug(String message, Object... arguments);

    /**
     * Logs a debug message with a throwable.
     *
     * @param message message template
     * @param error throwable to capture
     */
    void debug(String message, Throwable error);

    /**
     * Logs an informational message.
     *
     * @param message message template
     */
    void info(String message);

    /**
     * Logs an informational message with one positional argument.
     *
     * @param message message template
     * @param argument positional argument
     */
    void info(String message, Object argument);

    /**
     * Logs an informational message with two positional arguments.
     *
     * @param message message template
     * @param first first positional argument
     * @param second second positional argument
     */
    void info(String message, Object first, Object second);

    /**
     * Logs an informational message with positional arguments.
     *
     * @param message message template
     * @param arguments positional arguments
     */
    void info(String message, Object... arguments);

    /**
     * Logs an informational message with a throwable.
     *
     * @param message message template
     * @param error throwable to capture
     */
    void info(String message, Throwable error);

    /**
     * Logs a warning message.
     *
     * @param message message template
     */
    void warn(String message);

    /**
     * Logs a warning message with one positional argument.
     *
     * @param message message template
     * @param argument positional argument
     */
    void warn(String message, Object argument);

    /**
     * Logs a warning message with two positional arguments.
     *
     * @param message message template
     * @param first first positional argument
     * @param second second positional argument
     */
    void warn(String message, Object first, Object second);

    /**
     * Logs a warning message with positional arguments.
     *
     * @param message message template
     * @param arguments positional arguments
     */
    void warn(String message, Object... arguments);

    /**
     * Logs a warning message with a throwable.
     *
     * @param message message template
     * @param error throwable to capture
     */
    void warn(String message, Throwable error);

    /**
     * Logs an error message.
     *
     * @param message message template
     */
    void error(String message);

    /**
     * Logs an error message with one positional argument.
     *
     * @param message message template
     * @param argument positional argument
     */
    void error(String message, Object argument);

    /**
     * Logs an error message with two positional arguments.
     *
     * @param message message template
     * @param first first positional argument
     * @param second second positional argument
     */
    void error(String message, Object first, Object second);

    /**
     * Logs an error message with positional arguments.
     *
     * @param message message template
     * @param arguments positional arguments
     */
    void error(String message, Object... arguments);

    /**
     * Logs an error message with a throwable.
     *
     * @param message message template
     * @param error throwable to capture
     */
    void error(String message, Throwable error);

    /**
     * Adapter ingress preserving the caller's template, arguments, fields, and throwable.
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
     * Adapter ingress that also preserves source timestamp and logical thread metadata.
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
