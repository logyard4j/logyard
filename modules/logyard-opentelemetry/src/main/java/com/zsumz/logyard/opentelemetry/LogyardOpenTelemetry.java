package com.zsumz.logyard.opentelemetry;

import io.opentelemetry.api.OpenTelemetry;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Installs the application-owned OpenTelemetry instance used by {@code provider = "otel"} outputs.
 *
 * <p>Call {@link #install(OpenTelemetry)} before starting Logyard. Output creation fails if no
 * instance was installed; it never reads or initializes the OpenTelemetry global. The application
 * configures its SDK resource, processors and exporters, and owns their flush and shutdown.</p>
 *
 * <p>The same instance may be installed repeatedly. Replacing it is rejected because existing
 * outputs retain their bridge across configuration reloads. Trace capture uses the caller's
 * ambient context independently of this installation.</p>
 */
public final class LogyardOpenTelemetry {
    private static final AtomicReference<OpenTelemetry> INSTALLED = new AtomicReference<>();

    private LogyardOpenTelemetry() {
    }

    /**
     * Installs the OpenTelemetry instance whose logs bridge later-created {@code otel} outputs use.
     *
     * <p>The application keeps ownership of the instance: Logyard never builds, configures, closes,
     * or shuts it down.</p>
     *
     * @param openTelemetry application-owned OpenTelemetry instance
     * @throws IllegalStateException if a different instance is already installed
     */
    public static void install(OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        OpenTelemetry existing = INSTALLED.compareAndExchange(null, openTelemetry);
        if (existing != null && existing != openTelemetry) {
            throw new IllegalStateException("a different OpenTelemetry instance is already installed");
        }
    }

    /**
     * Returns the installed instance, or {@code null} when nothing was installed.
     *
     * @return installed OpenTelemetry instance, or {@code null}
     */
    static OpenTelemetry installedOrNull() {
        return INSTALLED.get();
    }

    /** Removes the installed instance so one test cannot leak its SDK into the next. */
    static void reset() {
        INSTALLED.set(null);
    }
}
