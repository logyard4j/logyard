package com.zsumz.logyard.test;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.routing.RouteDefinition;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.core.runtime.RuntimePlan;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A private Logyard runtime with bounded, synchronous event capture for assertions.
 *
 * <p>A kit owns its runtime outright. It never reads, writes, or installs the process-global
 * {@link com.zsumz.logyard.api.Logyard} holder, so any number of kits may exist at once and each
 * one sees only the events published through its own {@link #runtime()} or {@link #logger(String)}.
 * That makes a kit safe to create per test class, per test method, and on parallel threads without
 * a separate JVM.</p>
 *
 * <p>The root logger is routed at {@link Level#TRACE} into the kit's recorder, and delivery is
 * synchronous: no asynchronous wrapper stands between the logging call and the recorder, so a
 * published event is already visible in {@link #events()} when the logging call returns. There is
 * nothing to flush or await; concurrent events follow recorder acceptance order.</p>
 *
 * <p>Only code that logs through this kit is captured. Code logging through the static
 * {@link com.zsumz.logyard.api.Logyard} holder, through an installed SLF4J provider, or through any
 * other adapter bound to the process runtime reaches that process runtime instead, and this kit
 * records nothing for it. Capturing those ingress paths would require a global-capture mode that
 * takes over process-wide state; it is deliberately not part of this kit.</p>
 *
 * <p>Close the kit when the test finishes, ideally with try-with-resources:</p>
 *
 * <pre>{@code
 * try (LogyardTestKit kit = LogyardTestKit.isolated()) {
 *     kit.logger("com.example.Checkout").atInfo().add("order.id", 7L).log("order placed");
 *     kit.events().expect().level(Level.INFO).attribute("order.id", 7L).assertPresent();
 * }
 * }</pre>
 */
public final class LogyardTestKit implements AutoCloseable {
    /** Default maximum retained records per kit. */
    public static final int DEFAULT_CAPACITY = 1024;

    private static final String CAPTURE_OUTPUT = "capture";

    private final DefaultLogyardRuntime runtime;
    private final RecordedEvents events;

    private LogyardTestKit(DefaultLogyardRuntime runtime, RecordedEvents events) {
        this.runtime = runtime;
        this.events = events;
    }

    /**
     * Creates a private runtime retaining up to {@value #DEFAULT_CAPACITY} events.
     * Read operations and assertions fail after overflow; {@link RecordedEvents#clear()} resets capture.
     *
     * <p>The returned kit shares no state with the process-global runtime or with any other kit.</p>
     *
     * @return open isolated test kit
     */
    public static LogyardTestKit isolated() {
        return isolated(DEFAULT_CAPACITY);
    }

    /**
     * Creates a private runtime with an explicit retained-event limit.
     *
     * @param capacity maximum retained events, at least one; storage grows on demand
     * @return open isolated test kit
     * @throws IllegalArgumentException when capacity is not positive
     */
    public static LogyardTestKit isolated(int capacity) {
        RecordedEvents events = new RecordedEvents(capacity);
        RuntimePlan plan = new RuntimePlan(
                RouteDefinition.root(Level.TRACE, List.of(CAPTURE_OUTPUT), List.of()),
                Map.of(),
                Map.of(CAPTURE_OUTPUT, new RecordingSink(events)),
                Map.of());
        return new LogyardTestKit(new DefaultLogyardRuntime(plan), events);
    }

    /**
     * Returns the kit's private runtime.
     *
     * <p>Pass it to code under test that accepts a runtime. Closing it directly is equivalent to
     * {@link #close()}.</p>
     *
     * @return runtime owned by this kit
     */
    public LogyardRuntime runtime() {
        return runtime;
    }

    /**
     * Returns a logger named after a class.
     *
     * @param type class supplying the logger name
     * @return logger publishing into this kit
     */
    public LogyardLogger logger(Class<?> type) {
        return runtime.logger(type);
    }

    /**
     * Returns a logger with the supplied name.
     *
     * @param name logger name
     * @return logger publishing into this kit
     */
    public LogyardLogger logger(String name) {
        return runtime.logger(name);
    }

    /**
     * Returns the recorder holding every event published through this kit.
     *
     * @return event recorder owned by this kit
     */
    public RecordedEvents events() {
        return events;
    }

    /**
     * Shuts the private runtime down.
     *
     * <p>Idempotent. Already recorded events stay readable after closing; events logged through a
     * closed kit are dropped without an exception.</p>
     */
    @Override
    public void close() {
        runtime.close();
    }

    private record RecordingSink(RecordedEvents events) implements EventSink {
        private RecordingSink {
            Objects.requireNonNull(events, "events");
        }

        @Override
        public void accept(LogEvent event) {
            events.record(event);
        }
    }
}
