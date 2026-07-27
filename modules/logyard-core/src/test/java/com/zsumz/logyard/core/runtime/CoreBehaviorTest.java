package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogBuilder;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.event.MessageFormatter;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.delivery.CompositeSink;
import com.zsumz.logyard.core.diagnostics.EmergencyText;
import com.zsumz.logyard.core.failure.ComponentInvocationException;
import com.zsumz.logyard.core.processing.RedactionProcessor;
import com.zsumz.logyard.core.routing.RouteDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

public final class CoreBehaviorTest {
    @Test
    void formatsEscapesAndCircularArrays() {
        equal("hello world", MessageFormatter.format("hello {}", new Object[] {"world"}));
        equal("literal {}", MessageFormatter.format("literal \\{}", new Object[] {"ignored"}));
        equal("slash \\world", MessageFormatter.format("slash \\\\{}", new Object[] {"world"}));
        Object[] circular = new Object[1];
        circular[0] = circular;
        equal("[[shared reference]]", MessageFormatter.safeToString(circular));
    }


    @Test
    void boundsAndSanitizesEmergencyText() {
        equal("line\\nnext\\u202e", EmergencyText.sanitize("line\nnext\u202e", 64));
        String bounded = EmergencyText.sanitize("x".repeat(100), 16);
        equal(16, bounded.length());
        check(bounded.endsWith("…"), "truncated emergency text should end with an ellipsis");
    }

    @Test
    void routesByLongestPrefixAndSkipsDisabledSuppliers() {
        RecordingSink sink = new RecordingSink();
        RuntimePlan plan = new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("capture"), List.of()),
                Map.of(
                        "com.acme", new RouteDefinition(Level.DEBUG, null, null),
                        "com.acme.noisy", new RouteDefinition(Level.WARN, null, null)),
                Map.of("capture", sink),
                Map.of());
        try (LogyardRuntime runtime = new DefaultLogyardRuntime(plan)) {
            LogyardLogger enabled = runtime.logger("com.acme.Service");
            LogyardLogger noisy = runtime.logger("com.acme.noisy.Client");
            check(enabled.isDebugEnabled(), "com.acme should inherit DEBUG");
            check(!noisy.isInfoEnabled(), "longest prefix should raise noisy logger to WARN");
            AtomicBoolean evaluated = new AtomicBoolean();
            noisy.atDebug().argument(() -> {
                evaluated.set(true);
                return "expensive";
            }).log("not emitted {}");
            check(!evaluated.get(), "disabled suppliers must not be evaluated");
            enabled.debug("order {}", 7);
            equal(1, sink.events.size());
            equal("order 7", sink.events.get(0).renderedMessage());
        }
    }

    @Test
    void capturesMutableValuesAndArrays() {
        StringBuilder argument = new StringBuilder("before");
        List<String> items = new ArrayList<>(List.of("one"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("state", new StringBuilder("ready"));
        int[] numbers = {1, 2};
        LogEvent event = new LogEvent(
                1, 2, Level.INFO, "test", null, "{} {}", new Object[] {argument, numbers},
                AttributeSet.builder().put("items", items).put("metadata", metadata).put("numbers", numbers).build(),
                null, 1, "main");
        argument.append("-after");
        items.add("after");
        ((StringBuilder) metadata.get("state")).append("-after");
        numbers[0] = 99;
        String rendered = event.renderedMessage();
        equal("before [1, 2]", rendered);
        check(rendered == event.renderedMessage(), "rendered message should be cached for fanout");
        check(rendered == event.withAttributes(AttributeSet.EMPTY).renderedMessage(), "attribute copies should retain the rendered message");
        equal(List.of("one"), event.attributes().get("items"));
        equal(Map.of("state", "ready"), event.attributes().get("metadata"));
        equal(List.of(1, 2), event.attributes().get("numbers"));
        Object[] exposed = event.arguments();
        exposed[0] = "changed";
        equal("before [1, 2]", event.renderedMessage());
        @SuppressWarnings("unchecked")
        List<Object> immutable = (List<Object>) event.argumentAt(1);
        try {
            immutable.add(3);
            throw new AssertionError("captured array should be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }


    @Test
    void boundsThrowableVarargsAndDiscardedAttributeSuppliers() {
        RecordingSink sink = new RecordingSink();
        AtomicBoolean discardedSupplier = new AtomicBoolean();
        try (LogyardRuntime runtime = new DefaultLogyardRuntime(plan(sink))) {
            LogyardLogger logger = runtime.logger("test.Bounds");
            LogBuilder builder = logger.atInfo();
            for (int index = 0; index < CaptureLimits.MAX_ATTRIBUTES - 1; index++) {
                builder.add("field." + index, index);
            }
            builder.add("discarded", () -> {
                discardedSupplier.set(true);
                return "must-not-run";
            }).log("attributes");

            Object[] arguments = new Object[1_000];
            java.util.Arrays.fill(arguments, "value");
            IllegalStateException failure = new IllegalStateException("boom");
            arguments[arguments.length - 1] = failure;
            logger.info("{}", arguments);
        }

        check(!discardedSupplier.get(), "discarded attribute suppliers must not be evaluated");
        equal(true, sink.events.get(0).attributes().get("logyard.attributes.truncated"));
        LogEvent varargs = sink.events.get(1);
        equal(CaptureLimits.MAX_ARGUMENTS, varargs.argumentCount());
        equal(935, varargs.attributes().get("logyard.arguments.omitted"));
        equal("java.lang.IllegalStateException", varargs.exception().type());
    }

    @Test
    void fansOutPastFailingSinksAndRejectsInvalidPlans() {
        RecordingSink recording = new RecordingSink();
        AssertionError first = new AssertionError("first failed");
        AssertionError second = new AssertionError("second failed");
        CompositeSink composite = new CompositeSink(List.<EventSink>of(
                ignored -> { throw first; },
                recording,
                ignored -> { throw second; }));
        ComponentInvocationException fanoutFailure = expect(
                ComponentInvocationException.class,
                () -> composite.accept(event(AttributeSet.EMPTY)));
        equal(1, recording.events.size());
        check(fanoutFailure.getCause() == first, "the first fanout failure must remain primary");
        check(fanoutFailure.getMessage().contains("fanout output '0' accept"), "the first output identity must be retained");
        equal(1, fanoutFailure.getSuppressed().length);
        check(fanoutFailure.getSuppressed()[0].getCause() == second, "the second fanout failure must remain suppressed");

        expect(IllegalArgumentException.class, () -> new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("capture", "capture"), List.of()),
                Map.of(),
                Map.of("capture", recording),
                Map.of()));
        Map<String, EventSink> nullOutput = new LinkedHashMap<>();
        nullOutput.put("capture", null);
        expect(NullPointerException.class, () -> new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("capture"), List.of()),
                Map.of(),
                nullOutput,
                Map.of()));
    }

    @Test
    void reloadRetiresOutputsAfterInflightPublication() throws Exception {
        BlockingCloseSink oldSink = new BlockingCloseSink();
        RecordingSink newSink = new RecordingSink();
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(plan(oldSink));
        LogyardLogger logger = runtime.logger("test.Logger");
        Thread publisher = new Thread(() -> logger.info("old"));
        publisher.start();
        check(oldSink.entered.await(2, TimeUnit.SECONDS), "old sink should receive event");
        runtime.reload(plan(newSink));
        check(!oldSink.closed.get(), "old sink must remain open while an event is in flight");
        logger.info("new");
        equal(1, newSink.events.size());
        oldSink.release.countDown();
        publisher.join(2_000);
        check(oldSink.closedLatch.await(2, TimeUnit.SECONDS), "retired output should close after release");
        runtime.close();
    }


    @Test
    void makesFlushAfterCloseSafe() {
        LogyardRuntime runtime = new DefaultLogyardRuntime(plan(new RecordingSink()));
        runtime.close();
        runtime.flush();
    }

    @Test
    void redactsMatchingAttributesCaseInsensitively() {
        RedactionProcessor processor = new RedactionProcessor(List.of("*.token", "authorization", "password"));
        LogEvent redacted = processor.process(event(
                AttributeSet.builder()
                        .put("payment.token", "secret")
                        .put("Authorization", "bearer")
                        .put("mdc.authorization", "nested-bearer")
                        .put("mdc.credentials.password", "nested-password")
                        .put("request.id", "application-request")
                        .put("mdc.request.id", "mdc-request")
                        .put("order.id", "7")
                        .build()));
        equal("[REDACTED]", redacted.attributes().get("payment.token"));
        equal("[REDACTED]", redacted.attributes().get("Authorization"));
        equal("[REDACTED]", redacted.attributes().get("mdc.authorization"));
        equal("[REDACTED]", redacted.attributes().get("mdc.credentials.password"));
        equal("application-request", redacted.attributes().get("request.id"));
        equal("mdc-request", redacted.attributes().get("mdc.request.id"));
        equal("7", redacted.attributes().get("order.id"));

        LogEvent unchanged = event(AttributeSet.builder().put("order.id", "8").build());
        check(unchanged == processor.process(unchanged), "non-matching redaction must preserve the event instance");
    }

    private static RuntimePlan plan(EventSink sink) {
        return new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("capture"), List.of()),
                Map.of(), Map.of("capture", sink), Map.of(), Duration.ofSeconds(2));
    }

    private static LogEvent event(AttributeSet attributes) {
        return new LogEvent(1, 2, Level.INFO, "test", null, "message", null, attributes, null, 1, "main");
    }


    private static <T extends Throwable> T expect(Class<T> expected, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) {
                return expected.cast(failure);
            }
            throw new AssertionError("expected " + expected.getName() + " but caught " + failure, failure);
        }
        throw new AssertionError("expected " + expected.getName());
    }

    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.deepEquals(expected, actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class RecordingSink implements EventSink {
        private final List<LogEvent> events = new ArrayList<>();
        @Override public void accept(LogEvent event) { events.add(event); }
    }

    private static final class BlockingCloseSink implements EventSink {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void accept(LogEvent event) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("test sink was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }

        @Override
        public void close() {
            closed.set(true);
            closedLatch.countDown();
        }
    }
}
