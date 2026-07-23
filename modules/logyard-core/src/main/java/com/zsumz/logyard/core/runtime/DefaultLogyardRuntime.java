package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogBuilder;
import com.zsumz.logyard.api.Logyard;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.diagnostics.HealthStatus;
import com.zsumz.logyard.api.diagnostics.RuntimeHealth;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.ingress.IngressMetadata;
import com.zsumz.logyard.api.spi.EventProcessor;
import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.api.spi.HealthContributor;
import com.zsumz.logyard.core.delivery.CompositeSink;
import com.zsumz.logyard.core.diagnostics.EmergencyText;
import com.zsumz.logyard.core.routing.CompiledRoute;
import com.zsumz.logyard.api.diagnostics.EffectiveRoute;
import com.zsumz.logyard.core.routing.PlanEpoch;
import com.zsumz.logyard.core.routing.RouteDefinition;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private volatile RuntimeState state;

    public DefaultLogyardRuntime(RuntimePlan plan) {
        state = new RuntimeState(Objects.requireNonNull(plan, "plan"), new PlanEpoch());
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
            List<ComponentHealth> components = new ArrayList<>();
            Map<String, String> runtimeDetails = new LinkedHashMap<>();
            runtimeDetails.put("closed", "false");
            runtimeDetails.put("plan_retiring", Boolean.toString(snapshot.epoch().retiring()));
            Map<String, Long> runtimeMetrics = new LinkedHashMap<>();
            runtimeMetrics.put("logger_count", (long) loggers.size());
            runtimeMetrics.put("output_count", (long) snapshot.plan().outputs().size());
            runtimeMetrics.put("pending_retirements", (long) retirements.size());
            components.add(new ComponentHealth(
                    "runtime",
                    "runtime",
                    snapshot.epoch().retiring() ? HealthStatus.DEGRADED : HealthStatus.HEALTHY,
                    runtimeDetails,
                    runtimeMetrics));

            snapshot.plan().outputs().forEach((name, sink) -> components.add(outputHealth(name, sink)));
            return RuntimeHealth.from(components);
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
                    () -> closeOutputsNotReused(previous.plan(), next.plan()),
                    retirementExecutor::scheduleReload);
            retired = true;
            observeRetirement(retirement);
        } finally {
            if (!retired) {
                retirementExecutor.cancelReloadReservation();
            }
        }
    }

    void publish(
            String loggerName,
            LoggerControl control,
            Level level,
            String eventName,
            String messageTemplate,
            Object[] arguments,
            AttributeSet attributes,
            Throwable throwable,
            IngressMetadata metadata) {
        if (closed.get()) {
            return;
        }
        CompiledRoute route;
        while (true) {
            route = control.route;
            if (route.epoch().tryAcquire()) {
                break;
            }
            if (closed.get()) {
                return;
            }
            synchronized (this) {
                control.update(compileRoute(loggerName, state));
            }
        }
        try {
            if (!route.level().enables(level)) {
                return;
            }
            Thread thread = Thread.currentThread();
            Instant observed = Instant.now();
            long timestampMillis = metadata.hasSourceTimestamp()
                    ? metadata.sourceTimestampMillis()
                    : observed.toEpochMilli();
            long threadId = metadata.hasSourceThreadId()
                    ? metadata.sourceThreadId()
                    : metadata.sourceThreadName() == null ? thread.threadId() : -1L;
            String threadName = metadata.sourceThreadName() == null
                    ? thread.getName()
                    : metadata.sourceThreadName();
            LogEvent event = new LogEvent(
                    timestampMillis,
                    unixNanos(observed),
                    level,
                    loggerName,
                    eventName,
                    messageTemplate,
                    arguments,
                    attributes,
                    throwable,
                    threadId,
                    threadName);
            try {
                for (EventProcessor processor : route.processors()) {
                    event = processor.process(event);
                    if (event == null) {
                        return;
                    }
                }
                route.sink().accept(event);
            } catch (RuntimeException failure) {
                emergency(event, failure);
            }
        } finally {
            route.epoch().release();
        }
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
            for (EventSink sink : uniqueOutputs(snapshot.plan())) {
                sink.flush();
            }
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
                () -> closeAllOutputs(current.plan()),
                retirementExecutor::scheduleFinal);
        observeRetirement(finalRetirement);
        awaitRetirements(current.plan().shutdownTimeout());
    }

    private static CompiledRoute compileRoute(String loggerName, RuntimeState state) {
        RuntimePlan plan = state.plan();
        RouteDefinition effective = plan.root();
        String matched = "root";
        List<Map.Entry<String, RouteDefinition>> matches = new ArrayList<>();
        for (Map.Entry<String, RouteDefinition> entry : plan.loggers().entrySet()) {
            String rule = entry.getKey();
            if (loggerName.equals(rule) || loggerName.startsWith(rule + ".")) {
                matches.add(entry);
            }
        }
        matches.sort(Comparator.comparingInt(entry -> entry.getKey().length()));
        for (Map.Entry<String, RouteDefinition> match : matches) {
            effective = overlay(effective, match.getValue());
            matched = match.getKey();
        }

        List<String> outputNames = Objects.requireNonNull(effective.outputs(), "effective outputs");
        List<EventSink> sinks = new ArrayList<>(outputNames.size());
        for (String output : outputNames) {
            sinks.add(plan.outputs().get(output));
        }
        List<String> processorNames = Objects.requireNonNull(effective.processors(), "effective processors");
        EventProcessor[] processors = new EventProcessor[processorNames.size()];
        for (int index = 0; index < processorNames.size(); index++) {
            processors[index] = plan.processors().get(processorNames.get(index));
        }
        return new CompiledRoute(
                Objects.requireNonNull(effective.level(), "effective level"),
                new CompositeSink(sinks),
                processors,
                List.copyOf(outputNames),
                List.copyOf(processorNames),
                matched,
                state.epoch());
    }

    private static RouteDefinition overlay(RouteDefinition parent, RouteDefinition child) {
        return new RouteDefinition(
                child.level() == null ? parent.level() : child.level(),
                child.outputs() == null ? parent.outputs() : child.outputs(),
                child.processors() == null ? parent.processors() : child.processors());
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
        return new RuntimeHealth(
                Instant.now(),
                HealthStatus.STOPPED,
                false,
                List.of(new ComponentHealth(
                        "runtime",
                        "runtime",
                        HealthStatus.STOPPED,
                        Map.of("closed", "true"),
                        Map.of("logger_count", (long) loggers.size()))));
    }

    private static ComponentHealth outputHealth(String name, EventSink sink) {
        if (sink instanceof HealthContributor contributor) {
            try {
                return contributor.health(name);
            } catch (RuntimeException failure) {
                return new ComponentHealth(
                        name,
                        "output",
                        HealthStatus.FAILED,
                        Map.of("health_failure", failure.getClass().getName()),
                        Map.of());
            }
        }
        return ComponentHealth.healthy(name, "output");
    }

    private static List<EventSink> uniqueOutputs(RuntimePlan plan) {
        Set<EventSink> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<EventSink> result = new ArrayList<>();
        for (EventSink sink : plan.outputs().values()) {
            if (seen.add(sink)) {
                result.add(sink);
            }
        }
        return result;
    }

    private static void closeOutputsNotReused(RuntimePlan previous, RuntimePlan next) {
        Set<EventSink> reused = Collections.newSetFromMap(new IdentityHashMap<>());
        reused.addAll(next.outputs().values());
        for (EventSink sink : uniqueOutputs(previous)) {
            if (!reused.contains(sink)) {
                closeQuietly(sink);
            }
        }
    }

    private static void closeAllOutputs(RuntimePlan plan) {
        for (EventSink sink : uniqueOutputs(plan)) {
            closeQuietly(sink);
        }
    }

    private static void closeQuietly(EventSink sink) {
        try {
            sink.close();
        } catch (RuntimeException failure) {
            System.err.println("Logyard failed to close output: "
                    + EmergencyText.failureSummary(failure, 4_096));
        }
    }

    private static void emergency(LogEvent event, RuntimeException failure) {
        String body = EmergencyText.sanitize(event.renderedMessage(), CaptureLimits.MAX_TEXT_CHARS);
        System.err.println("Logyard delivery failure for " + event.level() + " "
                + EmergencyText.sanitize(event.loggerName(), CaptureLimits.MAX_NAME_CHARS)
                + " - " + body + ": " + EmergencyText.failureSummary(failure, 4_096));
    }

    private static long unixNanos(Instant instant) {
        return Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L),
                instant.getNano());
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
