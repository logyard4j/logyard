package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.diagnostics.EffectiveRoute;
import com.zsumz.logyard.api.diagnostics.RuntimeHealth;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.core.diagnostics.EmergencyText;
import com.zsumz.logyard.core.routing.CompiledRoute;
import com.zsumz.logyard.core.routing.PlanEpoch;
import com.zsumz.logyard.core.routing.RouteDefinition;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe runtime with compiled routes and lease-protected atomic plan replacement. */
public final class DefaultLogyardRuntime implements LogyardRuntime {
    private final ConcurrentHashMap<String, DefaultLogyardLogger> loggers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LoggerControl> controls = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> retirements = new ConcurrentLinkedQueue<>();
    private final RetirementExecutor retirementExecutor = new RetirementExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final RuntimeRouteLeases routeLeases;
    private final EventPublicationPipeline publicationPipeline;
    private volatile RuntimeState state;

    public DefaultLogyardRuntime(RuntimePlan plan) {
        state = new RuntimeState(Objects.requireNonNull(plan, "plan"), new PlanEpoch());
        routeLeases = new RuntimeRouteLeases(closed::get, this::refreshRoute);
        publicationPipeline = new EventPublicationPipeline(new EmergencyPublicationFailureHandler());
    }

    @Override
    public LogyardLogger logger(Class<?> type) {
        return logger(Objects.requireNonNull(type, "type").getName());
    }

    @Override
    public LogyardLogger logger(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("logger name must not be blank");
        }
        if (name.length() > CaptureLimits.MAX_NAME_CHARS) {
            throw new IllegalArgumentException(
                    "logger name exceeds " + CaptureLimits.MAX_NAME_CHARS + " characters");
        }
        return loggers.computeIfAbsent(name, key -> {
            synchronized (this) {
                LoggerControl control = new LoggerControl(compileRoute(key, state));
                controls.put(key, control);
                return new DefaultLogyardLogger(key, this, control);
            }
        });
    }

    @Override
    public EffectiveRoute explain(String loggerName) {
        Objects.requireNonNull(loggerName, "loggerName");
        CompiledRoute route = compileRoute(loggerName, state);
        return new EffectiveRoute(
                loggerName,
                route.level(),
                route.outputNames(),
                route.processorNames(),
                route.matchedRule());
    }

    @Override
    public RuntimeHealth health() {
        if (closed.get()) {
            return stoppedHealth();
        }

        RuntimeState snapshot;
        while (true) {
            snapshot = state;
            if (snapshot.epoch().tryAcquire()) {
                break;
            }
            if (closed.get()) {
                return stoppedHealth();
            }
        }
        try {
            return RuntimeHealthReporter.running(loggers.size(), retirements.size(), snapshot.plan(), snapshot.epoch());
        } finally {
            snapshot.epoch().release();
        }
    }

    public synchronized void reload(RuntimePlan nextPlan) {
        Objects.requireNonNull(nextPlan, "nextPlan");
        if (closed.get()) {
            throw new IllegalStateException("runtime is closed");
        }
        RuntimeState previous = state;
        RuntimeState next = new RuntimeState(nextPlan, new PlanEpoch());
        Map<String, CompiledRoute> compiled = new LinkedHashMap<>();
        controls.forEach((name, control) -> compiled.put(name, compileRoute(name, next)));
        if (!retirementExecutor.reserveReload()) {
            throw new IllegalStateException("Logyard has " + RetirementExecutor.MAX_PENDING_RELOADS
                    + " pending plan retirements; wait for output closure before reloading again");
        }
        boolean retired = false;
        try {
            state = next;
            compiled.forEach((name, route) -> controls.get(name).update(route));
            CompletableFuture<Void> retirement = previous.epoch().retire(
                    () -> RuntimeOutputs.closeNotReused(previous.plan(), next.plan()),
                    retirementExecutor::scheduleReload);
            retired = true;
            observeRetirement(retirement);
        } finally {
            if (!retired) {
                retirementExecutor.cancelReloadReservation();
            }
        }
    }

    void publish(LoggerControl control, EventDraft draft) {
        CompiledRouteLease lease = routeLeases.acquire(control, draft.loggerName());
        if (lease == null) {
            return;
        }
        try (lease) {
            publicationPipeline.publish(lease.route(), draft);
        }
    }

    private synchronized void refreshRoute(LoggerControl control, String loggerName) {
        control.update(compileRoute(loggerName, state));
    }

    @Override
    public void flush() {
        RuntimeState snapshot;
        while (true) {
            if (closed.get()) {
                return;
            }
            snapshot = state;
            if (snapshot.epoch().tryAcquire()) {
                break;
            }
        }
        try {
            RuntimeOutputs.flush(snapshot.plan());
        } finally {
            snapshot.epoch().release();
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeState current = state;
        CompletableFuture<Void> finalRetirement = current.epoch().retire(
                () -> RuntimeOutputs.closeAll(current.plan()),
                retirementExecutor::scheduleFinal);
        observeRetirement(finalRetirement);
        awaitRetirements(current.plan().shutdownTimeout());
    }

    private static CompiledRoute compileRoute(String loggerName, RuntimeState state) {
        return RuntimeRouteCompiler.compile(loggerName, state.plan(), state.epoch());
    }

    private void observeRetirement(CompletableFuture<Void> retirement) {
        retirements.add(retirement);
        retirement.whenComplete((ignored, failure) -> {
            retirements.remove(retirement);
            if (failure != null) {
                System.err.println("Logyard output retirement failed: "
                        + EmergencyText.failureSummary(failure, 4_096));
            }
        });
    }

    private void awaitRetirements(Duration timeout) {
        long timeoutNanos = saturatedNanos(timeout);
        long deadline = System.nanoTime() + timeoutNanos;
        for (CompletableFuture<Void> retirement : retirements) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                System.err.println("Logyard shutdown deadline elapsed before all outputs retired");
                return;
            }
            try {
                retirement.get(remaining, TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.TimeoutException timeoutFailure) {
                System.err.println("Logyard shutdown deadline elapsed before all outputs retired");
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException failure) {
                System.err.println("Logyard output retirement failed: "
                        + EmergencyText.failureSummary(failure.getCause(), 4_096));
            }
        }
    }


    private RuntimeHealth stoppedHealth() {
        return RuntimeHealthReporter.stopped(loggers.size());
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    public static DefaultLogyardRuntime consoleOnly(EventSink sink) {
        Map<String, EventSink> outputs = new LinkedHashMap<>();
        outputs.put("console", sink);
        return new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("console"), List.of()),
                Map.of(),
                outputs,
                Map.of()));
    }

    private record RuntimeState(RuntimePlan plan, PlanEpoch epoch) {
    }
}
