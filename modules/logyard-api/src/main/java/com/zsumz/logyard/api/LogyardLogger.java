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
    String name();

    boolean isEnabled(Level level);

    boolean isTraceEnabled();

    boolean isDebugEnabled();

    boolean isInfoEnabled();

    boolean isWarnEnabled();

    boolean isErrorEnabled();

    LogBuilder at(Level level);

    LogBuilder atTrace();

    LogBuilder atDebug();

    LogBuilder atInfo();

    LogBuilder atWarn();

    LogBuilder atError();

    void trace(String message);

    void trace(String message, Object argument);

    void trace(String message, Object first, Object second);

    void trace(String message, Object... arguments);

    void trace(String message, Throwable error);

    void debug(String message);

    void debug(String message, Object argument);

    void debug(String message, Object first, Object second);

    void debug(String message, Object... arguments);

    void debug(String message, Throwable error);

    void info(String message);

    void info(String message, Object argument);

    void info(String message, Object first, Object second);

    void info(String message, Object... arguments);

    void info(String message, Throwable error);

    void warn(String message);

    void warn(String message, Object argument);

    void warn(String message, Object first, Object second);

    void warn(String message, Object... arguments);

    void warn(String message, Throwable error);

    void error(String message);

    void error(String message, Object argument);

    void error(String message, Object first, Object second);

    void error(String message, Object... arguments);

    void error(String message, Throwable error);

    /** Adapter ingress preserving the caller's template, arguments, fields, and throwable. */
    void log(
            Level level,
            String eventName,
            String messageTemplate,
            Object[] arguments,
            AttributeSet attributes,
            Throwable throwable);

    /** Adapter ingress that also preserves source timestamp and logical thread metadata. */
    void log(
            Level level,
            String eventName,
            String messageTemplate,
            Object[] arguments,
            AttributeSet attributes,
            Throwable throwable,
            IngressMetadata metadata);
}
