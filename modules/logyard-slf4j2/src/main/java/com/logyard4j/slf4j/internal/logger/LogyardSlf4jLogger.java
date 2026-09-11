package com.logyard4j.slf4j.internal.logger;

import com.logyard4j.api.LogyardLogger;
import com.logyard4j.runtime.adapter.AdapterReentryGuard;
import com.logyard4j.slf4j.internal.diagnostics.ProviderDiagnostics;
import com.logyard4j.slf4j.internal.event.LevelMapper;
import com.logyard4j.slf4j.internal.event.Slf4jEventMapper;

import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Marker;
import org.slf4j.event.LoggingEvent;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.spi.LoggingEventAware;

/** SLF4J logger adapter that deliberately leaves templates and arguments separate. */
public final class LogyardSlf4jLogger extends AbstractLogger implements LoggingEventAware {
    private static final long serialVersionUID = 1L;
    private static final String FQCN = LogyardSlf4jLogger.class.getName();
    private static final AdapterReentryGuard REENTRY = new AdapterReentryGuard();

    private final transient Supplier<LogyardLogger> delegate;
    private final transient Slf4jEventMapper mapper;

    public LogyardSlf4jLogger(LogyardLogger delegate, Slf4jEventMapper mapper) {
        LogyardLogger resolved = Objects.requireNonNull(delegate, "delegate");
        this.delegate = () -> resolved;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        name = resolved.name();
    }

    public LogyardSlf4jLogger(
            String name,
            Supplier<LogyardLogger> delegate,
            Slf4jEventMapper mapper) {
        this.name = Objects.requireNonNull(name, "name");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override public boolean isTraceEnabled() { return enabled(org.slf4j.event.Level.TRACE); }
    @Override public boolean isDebugEnabled() { return enabled(org.slf4j.event.Level.DEBUG); }
    @Override public boolean isInfoEnabled() { return enabled(org.slf4j.event.Level.INFO); }
    @Override public boolean isWarnEnabled() { return enabled(org.slf4j.event.Level.WARN); }
    @Override public boolean isErrorEnabled() { return enabled(org.slf4j.event.Level.ERROR); }
    @Override public boolean isTraceEnabled(Marker marker) { return isTraceEnabled(); }
    @Override public boolean isDebugEnabled(Marker marker) { return isDebugEnabled(); }
    @Override public boolean isInfoEnabled(Marker marker) { return isInfoEnabled(); }
    @Override public boolean isWarnEnabled(Marker marker) { return isWarnEnabled(); }
    @Override public boolean isErrorEnabled(Marker marker) { return isErrorEnabled(); }

    @Override
    protected String getFullyQualifiedCallerName() {
        return FQCN;
    }

    @Override
    protected void handleNormalizedLoggingCall(
            org.slf4j.event.Level level,
            Marker marker,
            String messagePattern,
            Object[] arguments,
            Throwable throwable) {
        if (!REENTRY.enter()) {
            return;
        }
        try {
            mapper.publishNormalized(resolveDelegate(), level, marker, messagePattern, arguments, throwable);
        } catch (Throwable failure) {
            ProviderDiagnostics.rethrowIfFatal(failure);
            ProviderDiagnostics.eventMappingFailure(name, failure);
        } finally {
            REENTRY.exit();
        }
    }

    @Override
    public void log(LoggingEvent event) {
        if (!REENTRY.enter()) {
            return;
        }
        try {
            mapper.publish(resolveDelegate(), event);
        } catch (Throwable failure) {
            ProviderDiagnostics.rethrowIfFatal(failure);
            ProviderDiagnostics.eventMappingFailure(name, failure);
        } finally {
            REENTRY.exit();
        }
    }

    private boolean enabled(org.slf4j.event.Level level) {
        if (!REENTRY.enter()) {
            return false;
        }
        try {
            return resolveDelegate().isEnabled(LevelMapper.toLogyard(level));
        } catch (Throwable failure) {
            ProviderDiagnostics.rethrowIfFatal(failure);
            ProviderDiagnostics.eventMappingFailure(name, failure);
            return false;
        } finally {
            REENTRY.exit();
        }
    }

    private LogyardLogger resolveDelegate() {
        return Objects.requireNonNull(delegate.get(), "delegate logger");
    }
}
