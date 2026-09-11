package com.logyard4j.systemlogger.internal.factory;

import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.ingress.LogEventIngress;
import com.logyard4j.runtime.adapter.AdapterReentryGuard;
import com.logyard4j.runtime.adapter.AdapterRuntimeAccess;
import com.logyard4j.runtime.diagnostics.AdapterDiagnostics;
import com.logyard4j.systemlogger.internal.event.SystemLevelMapper;
import com.logyard4j.systemlogger.internal.event.SystemMessageRenderer;

import java.util.Objects;
import java.util.ResourceBundle;

/** System.Logger adapter with lazy runtime acquisition and typed module metadata. */
public final class LogyardSystemLogger implements System.Logger {
    private static final AdapterReentryGuard REENTRY = new AdapterReentryGuard();

    private final String name;
    private final String moduleName;
    private final AdapterRuntimeAccess runtime;

    public LogyardSystemLogger(String name, Module module, AdapterRuntimeAccess runtime) {
        this.name = requireName(name);
        moduleName = module == null || module.getName() == null
                ? "unnamed"
                : module.getName();
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isLoggable(System.Logger.Level sourceLevel) {
        Objects.requireNonNull(sourceLevel, "level");
        if (sourceLevel == System.Logger.Level.OFF || !REENTRY.enter()) {
            return false;
        }
        try {
            LogEventIngress logger = runtime.runtime().logger(name);
            return logger.isEnabled(SystemLevelMapper.toLogyard(sourceLevel));
        } catch (Throwable failure) {
            AdapterDiagnostics.rethrowIfFatal(failure);
            AdapterDiagnostics.adapterFailure("system-logger", "level check", failure);
            return false;
        } finally {
            REENTRY.exit();
        }
    }

    @Override
    public void log(
            System.Logger.Level level,
            ResourceBundle bundle,
            String message,
            Throwable thrown) {
        publish(level, bundle, message, null, thrown);
    }

    @Override
    public void log(
            System.Logger.Level level,
            ResourceBundle bundle,
            String format,
            Object... parameters) {
        publish(level, bundle, format, parameters, null);
    }

    private void publish(
            System.Logger.Level sourceLevel,
            ResourceBundle bundle,
            String message,
            Object[] parameters,
            Throwable thrown) {
        Objects.requireNonNull(sourceLevel, "level");
        if (sourceLevel == System.Logger.Level.OFF || !REENTRY.enter()) {
            return;
        }
        try {
            com.logyard4j.api.Level logyardLevel = SystemLevelMapper.toLogyard(sourceLevel);
            LogEventIngress logger = runtime.runtime().logger(name);
            if (!logger.isEnabled(logyardLevel)) {
                return;
            }
            SystemMessageRenderer.Result rendered =
                    SystemMessageRenderer.render(bundle, message, parameters);
            AttributeSet.Builder attributes = AttributeSet.builder()
                    .put("java.module.name", moduleName)
                    .put("system.logger.level", sourceLevel.name());
            String bundleName = bundle == null ? null : bundle.getBaseBundleName();
            if (bundleName != null) {
                attributes.put("system.logger.resource_bundle", bundleName);
            }
            if (rendered.template() != null && !Objects.equals(rendered.template(), rendered.message())) {
                attributes.put("system.logger.message_template", rendered.template());
            }
            AttributeSet captured = attributes.build();
            if (rendered.formatFailed()) {
                captured = captured.mergedWith(
                        AttributeSet.systemBuilder(1).put("logyard.system_logger.message_format_failed", true).build());
            }
            if (rendered.truncated()) {
                captured = captured.mergedWith(
                        AttributeSet.systemBuilder(1).put("logyard.capture.truncated", true).build());
            }
            logger.log(logyardLevel, null, rendered.message(), null, captured, thrown);
        } catch (Throwable failure) {
            AdapterDiagnostics.rethrowIfFatal(failure);
            AdapterDiagnostics.adapterFailure("system-logger", "event capture", failure);
        } finally {
            REENTRY.exit();
        }
    }

    private static String requireName(String value) {
        String normalized = Objects.requireNonNull(value, "name").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("System.Logger name must not be blank");
        }
        return normalized;
    }
}
