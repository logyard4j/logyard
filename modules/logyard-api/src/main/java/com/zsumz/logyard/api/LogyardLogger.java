package com.zsumz.logyard.api;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.ingress.LogEventIngress;

/**
 * Native logger contract with level checks before capture or supplier evaluation.
 *
 * <p>Two-argument and varargs convenience methods extract a trailing Throwable as the
 * cause, independently of the number of placeholders, following SLF4J normalization.
 * An explicitly typed {@code (String, Throwable)} call always records its cause;
 * use {@code atInfo().argument(value)} to render a throwable as an ordinary value.</p>
 *
 * <p>The {@code with} methods attach preset attributes to every event. Event attributes
 * override presets, which override scoped context. Repeated presets merge into one wrapper.</p>
 */
public interface LogyardLogger extends LogEventIngress {
    /** Reports trace-level enablement.
     * @return whether trace events are enabled
     */
    default boolean isTraceEnabled() { return isEnabled(Level.TRACE); }
    /** Reports debug-level enablement.
     * @return whether debug events are enabled
     */
    default boolean isDebugEnabled() { return isEnabled(Level.DEBUG); }
    /** Reports informational-level enablement.
     * @return whether informational events are enabled
     */
    default boolean isInfoEnabled() { return isEnabled(Level.INFO); }
    /** Reports warning-level enablement.
     * @return whether warning events are enabled
     */
    default boolean isWarnEnabled() { return isEnabled(Level.WARN); }
    /** Reports error-level enablement.
     * @return whether error events are enabled
     */
    default boolean isErrorEnabled() { return isEnabled(Level.ERROR); }

    /** Starts a structured event at the supplied level.
     * @param level event level
     * @return structured event builder
     */
    LogBuilder at(Level level);
    /** Starts a structured trace event.
     * @return structured trace-event builder
     */
    default LogBuilder atTrace() { return at(Level.TRACE); }
    /** Starts a structured debug event.
     * @return structured debug-event builder
     */
    default LogBuilder atDebug() { return at(Level.DEBUG); }
    /** Starts a structured informational event.
     * @return structured informational-event builder
     */
    default LogBuilder atInfo() { return at(Level.INFO); }
    /** Starts a structured warning event.
     * @return structured warning-event builder
     */
    default LogBuilder atWarn() { return at(Level.WARN); }
    /** Starts a structured error event.
     * @return structured error-event builder
     */
    default LogBuilder atError() { return at(Level.ERROR); }

    /** Returns a logger presetting attributes on every event it publishes.
     * @param attributes preset attributes
     * @return curried logger
     */
    default LogyardLogger with(AttributeSet attributes) { return AttributedLogger.of(this, attributes); }
    /** Returns a logger presetting one attribute on every event it publishes.
     * @param key attribute key
     * @param value attribute value
     * @return curried logger
     */
    default LogyardLogger with(String key, Object value) { return with(AttributeSet.of(key, value)); }

    /** Logs a trace message.
     * @param message trace message template
     */
    default void trace(String message) { LoggerConvenience.log(this, Level.TRACE, message); }
    /** Logs a trace message with one argument.
     * @param message trace message template
     * @param argument positional argument
     */
    default void trace(String message, Object argument) { LoggerConvenience.log(this, Level.TRACE, message, argument); }
    /** Logs a trace message with two arguments.
     * @param message trace message template
     * @param first first positional argument
     * @param second second positional argument
     */
    default void trace(String message, Object first, Object second) { LoggerConvenience.log(this, Level.TRACE, message, first, second); }
    /** Logs a trace message with positional arguments.
     * @param message trace message template
     * @param arguments positional arguments
     */
    default void trace(String message, Object... arguments) { LoggerConvenience.log(this, Level.TRACE, message, arguments); }
    /** Logs a trace message with a throwable.
     * @param message trace message template
     * @param error throwable to capture
     */
    default void trace(String message, Throwable error) { LoggerConvenience.log(this, Level.TRACE, message, error); }

    /** Logs a debug message.
     * @param message debug message template
     */
    default void debug(String message) { LoggerConvenience.log(this, Level.DEBUG, message); }
    /** Logs a debug message with one argument.
     * @param message debug message template
     * @param argument positional argument
     */
    default void debug(String message, Object argument) { LoggerConvenience.log(this, Level.DEBUG, message, argument); }
    /** Logs a debug message with two arguments.
     * @param message debug message template
     * @param first first positional argument
     * @param second second positional argument
     */
    default void debug(String message, Object first, Object second) { LoggerConvenience.log(this, Level.DEBUG, message, first, second); }
    /** Logs a debug message with positional arguments.
     * @param message debug message template
     * @param arguments positional arguments
     */
    default void debug(String message, Object... arguments) { LoggerConvenience.log(this, Level.DEBUG, message, arguments); }
    /** Logs a debug message with a throwable.
     * @param message debug message template
     * @param error throwable to capture
     */
    default void debug(String message, Throwable error) { LoggerConvenience.log(this, Level.DEBUG, message, error); }

    /** Logs an informational message.
     * @param message informational message template
     */
    default void info(String message) { LoggerConvenience.log(this, Level.INFO, message); }
    /** Logs an informational message with one argument.
     * @param message informational message template
     * @param argument positional argument
     */
    default void info(String message, Object argument) { LoggerConvenience.log(this, Level.INFO, message, argument); }
    /** Logs an informational message with two arguments.
     * @param message informational message template
     * @param first first positional argument
     * @param second second positional argument
     */
    default void info(String message, Object first, Object second) { LoggerConvenience.log(this, Level.INFO, message, first, second); }
    /** Logs an informational message with positional arguments.
     * @param message informational message template
     * @param arguments positional arguments
     */
    default void info(String message, Object... arguments) { LoggerConvenience.log(this, Level.INFO, message, arguments); }
    /** Logs an informational message with a throwable.
     * @param message informational message template
     * @param error throwable to capture
     */
    default void info(String message, Throwable error) { LoggerConvenience.log(this, Level.INFO, message, error); }

    /** Logs a warning message.
     * @param message warning message template
     */
    default void warn(String message) { LoggerConvenience.log(this, Level.WARN, message); }
    /** Logs a warning message with one argument.
     * @param message warning message template
     * @param argument positional argument
     */
    default void warn(String message, Object argument) { LoggerConvenience.log(this, Level.WARN, message, argument); }
    /** Logs a warning message with two arguments.
     * @param message warning message template
     * @param first first positional argument
     * @param second second positional argument
     */
    default void warn(String message, Object first, Object second) { LoggerConvenience.log(this, Level.WARN, message, first, second); }
    /** Logs a warning message with positional arguments.
     * @param message warning message template
     * @param arguments positional arguments
     */
    default void warn(String message, Object... arguments) { LoggerConvenience.log(this, Level.WARN, message, arguments); }
    /** Logs a warning message with a throwable.
     * @param message warning message template
     * @param error throwable to capture
     */
    default void warn(String message, Throwable error) { LoggerConvenience.log(this, Level.WARN, message, error); }

    /** Logs an error message.
     * @param message error message template
     */
    default void error(String message) { LoggerConvenience.log(this, Level.ERROR, message); }
    /** Logs an error message with one argument.
     * @param message error message template
     * @param argument positional argument
     */
    default void error(String message, Object argument) { LoggerConvenience.log(this, Level.ERROR, message, argument); }
    /** Logs an error message with two arguments.
     * @param message error message template
     * @param first first positional argument
     * @param second second positional argument
     */
    default void error(String message, Object first, Object second) { LoggerConvenience.log(this, Level.ERROR, message, first, second); }
    /** Logs an error message with positional arguments.
     * @param message error message template
     * @param arguments positional arguments
     */
    default void error(String message, Object... arguments) { LoggerConvenience.log(this, Level.ERROR, message, arguments); }
    /** Logs an error message with a throwable.
     * @param message error message template
     * @param error throwable to capture
     */
    default void error(String message, Throwable error) { LoggerConvenience.log(this, Level.ERROR, message, error); }
}
