package com.zsumz.logyard.api;

import com.zsumz.logyard.api.ingress.LogEventIngress;

/**
 * Stable native logger contract.
 *
 * <p>Implementations must perform level checks before inspecting arguments, suppliers, context,
 * clocks, or thread metadata.</p>
 */
public interface LogyardLogger extends LogEventIngress {
    /**
     * Returns whether trace events are enabled.
     *
     * @return {@code true} when trace is enabled
     */
    default boolean isTraceEnabled() { return isEnabled(Level.TRACE); }

    /**
     * Returns whether debug events are enabled.
     *
     * @return {@code true} when debug is enabled
     */
    default boolean isDebugEnabled() { return isEnabled(Level.DEBUG); }

    /**
     * Returns whether informational events are enabled.
     *
     * @return {@code true} when info is enabled
     */
    default boolean isInfoEnabled() { return isEnabled(Level.INFO); }

    /**
     * Returns whether warning events are enabled.
     *
     * @return {@code true} when warn is enabled
     */
    default boolean isWarnEnabled() { return isEnabled(Level.WARN); }

    /**
     * Returns whether error events are enabled.
     *
     * @return {@code true} when error is enabled
     */
    default boolean isErrorEnabled() { return isEnabled(Level.ERROR); }

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
    default LogBuilder atTrace() { return at(Level.TRACE); }

    /**
     * Starts a structured debug event.
     *
     * @return event builder
     */
    default LogBuilder atDebug() { return at(Level.DEBUG); }

    /**
     * Starts a structured informational event.
     *
     * @return event builder
     */
    default LogBuilder atInfo() { return at(Level.INFO); }

    /**
     * Starts a structured warning event.
     *
     * @return event builder
     */
    default LogBuilder atWarn() { return at(Level.WARN); }

    /**
     * Starts a structured error event.
     *
     * @return event builder
     */
    default LogBuilder atError() { return at(Level.ERROR); }

    /**
     * Logs a trace message.
     *
     * @param message message template
     */
    default void trace(String message) { LoggerConvenience.log(this, Level.TRACE, message); }

    /**
     * Logs a trace message with one positional argument.
     *
     * @param message message template
     * @param argument positional argument
     */
    default void trace(String message, Object argument) { LoggerConvenience.log(this, Level.TRACE, message, argument); }

    /**
     * Logs a trace message with two positional arguments.
     *
     * @param message message template
     * @param first first positional argument
     * @param second second positional argument
     */
    default void trace(String message, Object first, Object second) { LoggerConvenience.log(this, Level.TRACE, message, first, second); }

    /**
     * Logs a trace message with positional arguments.
     *
     * @param message message template
     * @param arguments positional arguments
     */
    default void trace(String message, Object... arguments) { LoggerConvenience.log(this, Level.TRACE, message, arguments); }

    /**
     * Logs a trace message with a throwable.
     *
     * @param message message template
     * @param error throwable to capture
     */
    default void trace(String message, Throwable error) { LoggerConvenience.log(this, Level.TRACE, message, error); }

    /**
     * Logs a debug message.
     *
     * @param message message template
     */
    default void debug(String message) { LoggerConvenience.log(this, Level.DEBUG, message); }

    /**
     * Logs a debug message with one positional argument.
     *
     * @param message message template
     * @param argument positional argument
     */
    default void debug(String message, Object argument) { LoggerConvenience.log(this, Level.DEBUG, message, argument); }

    /**
     * Logs a debug message with two positional arguments.
     *
     * @param message message template
     * @param first first positional argument
     * @param second second positional argument
     */
    default void debug(String message, Object first, Object second) { LoggerConvenience.log(this, Level.DEBUG, message, first, second); }

    /**
     * Logs a debug message with positional arguments.
     *
     * @param message message template
     * @param arguments positional arguments
     */
    default void debug(String message, Object... arguments) { LoggerConvenience.log(this, Level.DEBUG, message, arguments); }

    /**
     * Logs a debug message with a throwable.
     *
     * @param message message template
     * @param error throwable to capture
     */
    default void debug(String message, Throwable error) { LoggerConvenience.log(this, Level.DEBUG, message, error); }

    /**
     * Logs an informational message.
     *
     * @param message message template
     */
    default void info(String message) { LoggerConvenience.log(this, Level.INFO, message); }

    /**
     * Logs an informational message with one positional argument.
     *
     * @param message message template
     * @param argument positional argument
     */
    default void info(String message, Object argument) { LoggerConvenience.log(this, Level.INFO, message, argument); }

    /**
     * Logs an informational message with two positional arguments.
     *
     * @param message message template
     * @param first first positional argument
     * @param second second positional argument
     */
    default void info(String message, Object first, Object second) { LoggerConvenience.log(this, Level.INFO, message, first, second); }

    /**
     * Logs an informational message with positional arguments.
     *
     * @param message message template
     * @param arguments positional arguments
     */
    default void info(String message, Object... arguments) { LoggerConvenience.log(this, Level.INFO, message, arguments); }

    /**
     * Logs an informational message with a throwable.
     *
     * @param message message template
     * @param error throwable to capture
     */
    default void info(String message, Throwable error) { LoggerConvenience.log(this, Level.INFO, message, error); }

    /**
     * Logs a warning message.
     *
     * @param message message template
     */
    default void warn(String message) { LoggerConvenience.log(this, Level.WARN, message); }

    /**
     * Logs a warning message with one positional argument.
     *
     * @param message message template
     * @param argument positional argument
     */
    default void warn(String message, Object argument) { LoggerConvenience.log(this, Level.WARN, message, argument); }

    /**
     * Logs a warning message with two positional arguments.
     *
     * @param message message template
     * @param first first positional argument
     * @param second second positional argument
     */
    default void warn(String message, Object first, Object second) { LoggerConvenience.log(this, Level.WARN, message, first, second); }

    /**
     * Logs a warning message with positional arguments.
     *
     * @param message message template
     * @param arguments positional arguments
     */
    default void warn(String message, Object... arguments) { LoggerConvenience.log(this, Level.WARN, message, arguments); }

    /**
     * Logs a warning message with a throwable.
     *
     * @param message message template
     * @param error throwable to capture
     */
    default void warn(String message, Throwable error) { LoggerConvenience.log(this, Level.WARN, message, error); }

    /**
     * Logs an error message.
     *
     * @param message message template
     */
    default void error(String message) { LoggerConvenience.log(this, Level.ERROR, message); }

    /**
     * Logs an error message with one positional argument.
     *
     * @param message message template
     * @param argument positional argument
     */
    default void error(String message, Object argument) { LoggerConvenience.log(this, Level.ERROR, message, argument); }

    /**
     * Logs an error message with two positional arguments.
     *
     * @param message message template
     * @param first first positional argument
     * @param second second positional argument
     */
    default void error(String message, Object first, Object second) { LoggerConvenience.log(this, Level.ERROR, message, first, second); }

    /**
     * Logs an error message with positional arguments.
     *
     * @param message message template
     * @param arguments positional arguments
     */
    default void error(String message, Object... arguments) { LoggerConvenience.log(this, Level.ERROR, message, arguments); }

    /**
     * Logs an error message with a throwable.
     *
     * @param message message template
     * @param error throwable to capture
     */
    default void error(String message, Throwable error) { LoggerConvenience.log(this, Level.ERROR, message, error); }

}
