package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogBuilder;
import com.zsumz.logyard.api.Logyard;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.ingress.IngressMetadata;
import com.zsumz.logyard.api.spi.EventProcessor;
import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.core.delivery.CompositeSink;
import com.zsumz.logyard.core.diagnostics.EmergencyText;
import com.zsumz.logyard.core.routing.CompiledRoute;
import com.zsumz.logyard.api.diagnostics.EffectiveRoute;
import com.zsumz.logyard.core.routing.PlanEpoch;
import com.zsumz.logyard.core.routing.RouteDefinition;

import java.util.Arrays;
import java.util.Objects;

final class DefaultLogyardLogger implements com.zsumz.logyard.api.LogyardLogger {
    private final String name;
    private final DefaultLogyardRuntime runtime;
    private final LoggerControl control;

    DefaultLogyardLogger(String name, DefaultLogyardRuntime runtime, LoggerControl control) {
        this.name = name;
        this.runtime = runtime;
        this.control = control;
    }

    public String name() { return name; }
    public boolean isEnabled(Level level) { return control.enabled(Objects.requireNonNull(level, "level")); }
    public boolean isTraceEnabled() { return isEnabled(Level.TRACE); }
    public boolean isDebugEnabled() { return isEnabled(Level.DEBUG); }
    public boolean isInfoEnabled() { return isEnabled(Level.INFO); }
    public boolean isWarnEnabled() { return isEnabled(Level.WARN); }
    public boolean isErrorEnabled() { return isEnabled(Level.ERROR); }

    public LogBuilder at(Level level) {
        return isEnabled(level) ? new ActiveLogBuilder(this, level) : NoopLogBuilder.INSTANCE;
    }
    public LogBuilder atTrace() { return at(Level.TRACE); }
    public LogBuilder atDebug() { return at(Level.DEBUG); }
    public LogBuilder atInfo() { return at(Level.INFO); }
    public LogBuilder atWarn() { return at(Level.WARN); }
    public LogBuilder atError() { return at(Level.ERROR); }

    public void trace(String message) { publish0(Level.TRACE, message, null); }
    public void trace(String message, Object arg) { publish1(Level.TRACE, message, arg); }
    public void trace(String message, Object a, Object b) { publish2(Level.TRACE, message, a, b); }
    public void trace(String message, Object... args) { publishVarargs(Level.TRACE, message, args); }
    public void trace(String message, Throwable error) { publish0(Level.TRACE, message, error); }
    public void debug(String message) { publish0(Level.DEBUG, message, null); }
    public void debug(String message, Object arg) { publish1(Level.DEBUG, message, arg); }
    public void debug(String message, Object a, Object b) { publish2(Level.DEBUG, message, a, b); }
    public void debug(String message, Object... args) { publishVarargs(Level.DEBUG, message, args); }
    public void debug(String message, Throwable error) { publish0(Level.DEBUG, message, error); }
    public void info(String message) { publish0(Level.INFO, message, null); }
    public void info(String message, Object arg) { publish1(Level.INFO, message, arg); }
    public void info(String message, Object a, Object b) { publish2(Level.INFO, message, a, b); }
    public void info(String message, Object... args) { publishVarargs(Level.INFO, message, args); }
    public void info(String message, Throwable error) { publish0(Level.INFO, message, error); }
    public void warn(String message) { publish0(Level.WARN, message, null); }
    public void warn(String message, Object arg) { publish1(Level.WARN, message, arg); }
    public void warn(String message, Object a, Object b) { publish2(Level.WARN, message, a, b); }
    public void warn(String message, Object... args) { publishVarargs(Level.WARN, message, args); }
    public void warn(String message, Throwable error) { publish0(Level.WARN, message, error); }
    public void error(String message) { publish0(Level.ERROR, message, null); }
    public void error(String message, Object arg) { publish1(Level.ERROR, message, arg); }
    public void error(String message, Object a, Object b) { publish2(Level.ERROR, message, a, b); }
    public void error(String message, Object... args) { publishVarargs(Level.ERROR, message, args); }
    public void error(String message, Throwable error) { publish0(Level.ERROR, message, error); }

    /** Adapter entry point used by SLF4J and future ingress modules. */
    @Override
    public void log(
            Level level,
            String eventName,
            String messageTemplate,
            Object[] arguments,
            AttributeSet attributes,
            Throwable throwable) {
        log(
                level,
                eventName,
                messageTemplate,
                arguments,
                attributes,
                throwable,
                IngressMetadata.current());
    }

    @Override
    public void log(
            Level level,
            String eventName,
            String messageTemplate,
            Object[] arguments,
            AttributeSet attributes,
            Throwable throwable,
            IngressMetadata metadata) {
        if (isEnabled(level)) {
            publish(level, eventName, messageTemplate, arguments, attributes, throwable, metadata);
        }
    }

    void publish(
            Level level,
            String eventName,
            String template,
            Object[] arguments,
            AttributeSet attributes,
            Throwable throwable) {
        publish(
                level,
                eventName,
                template,
                arguments,
                attributes,
                throwable,
                IngressMetadata.current());
    }

    private void publish(
            Level level,
            String eventName,
            String template,
            Object[] arguments,
            AttributeSet attributes,
            Throwable throwable,
            IngressMetadata metadata) {
        runtime.publish(
                name,
                control,
                level,
                eventName,
                template,
                arguments,
                attributes,
                throwable,
                Objects.requireNonNull(metadata, "metadata"));
    }

    private void publish0(Level level, String message, Throwable throwable) {
        if (control.enabled(level)) {
            publish(level, null, message, null, AttributeSet.EMPTY, throwable);
        }
    }

    private void publish1(Level level, String message, Object arg) {
        if (control.enabled(level)) {
            publish(level, null, message, new Object[] {arg}, AttributeSet.EMPTY, null);
        }
    }

    private void publish2(Level level, String message, Object a, Object b) {
        if (control.enabled(level)) {
            publish(level, null, message, new Object[] {a, b}, AttributeSet.EMPTY, null);
        }
    }

    private void publishVarargs(Level level, String message, Object[] args) {
        if (!control.enabled(level)) {
            return;
        }
        Throwable throwable = null;
        Object[] actual = args;
        AttributeSet attributes = AttributeSet.EMPTY;
        if (args != null && args.length > 0 && args[args.length - 1] instanceof Throwable candidate) {
            throwable = candidate;
            int supplied = args.length - 1;
            int retained = Math.min(supplied, CaptureLimits.MAX_ARGUMENTS);
            actual = Arrays.copyOf(args, retained);
            if (supplied > retained) {
                attributes = AttributeSet.of("logyard.arguments.omitted", supplied - retained);
            }
        }
        publish(level, null, message, actual, attributes, throwable);
    }
}
