package com.zsumz.logyard.slf4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.routing.RouteDefinition;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.core.runtime.RuntimePlan;
import com.zsumz.logyard.runtime.adapter.AdapterRuntimeAccess;
import com.zsumz.logyard.slf4j.internal.context.ContextSnapshotPolicy;
import com.zsumz.logyard.slf4j.internal.context.LogyardMdcAdapter;
import com.zsumz.logyard.slf4j.internal.event.Slf4jEventMapper;
import com.zsumz.logyard.slf4j.internal.factory.LogyardLoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

final class Slf4jLifecycleRecoveryTest {
    @Test
    void serviceProviderInitializationDefersRuntimeAcquisition() {
        LogyardServiceProvider provider = new LogyardServiceProvider();

        provider.initialize();
        Logger logger = provider.getLoggerFactory().getLogger("test.Deferred");

        assertNotNull(logger);
        assertFalse(provider.ownsRuntime());
    }

    @Test
    void existingLoggerRetriesAfterTemporaryRuntimeUnavailability() {
        RecordingSink sink = new RecordingSink();
        try (LogyardRuntime runtime = runtime(sink)) {
            AtomicBoolean first = new AtomicBoolean(true);
            AdapterRuntimeAccess retrying = new AdapterRuntimeAccess() {
                @Override
                public LogyardRuntime runtime() {
                    if (first.compareAndSet(true, false)) {
                        throw new IllegalStateException("runtime transition in progress");
                    }
                    return runtime;
                }

                @Override public boolean initialized() { return !first.get(); }
                @Override public void close() { }
            };
            Logger logger = logger(retrying, new LogyardMdcAdapter(), List.of(), "test.Retry");

            logger.info("dropped during transition");
            logger.info("accepted after transition");

            assertEquals(1, sink.events.size());
            assertEquals("accepted after transition", sink.events.getFirst().messageTemplate());
        }
    }

    @Test
    void payloadExhaustionNeverCreatesEmptyOrDuplicateMdcKeys() {
        RecordingSink sink = new RecordingSink();
        try (LogyardRuntime runtime = runtime(sink)) {
            LogyardMdcAdapter mdc = new LogyardMdcAdapter();
            mdc.put("request.id", "request-7");
            Logger logger = new LogyardLoggerFactory(
                    runtime,
                    new Slf4jEventMapper(mdc, new ContextSnapshotPolicy(List.of("request.id"))))
                    .getLogger("test.PayloadBudget");

            logger.info("{}", "x".repeat(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS));

            AttributeSet attributes = sink.events.getFirst().attributes();
            HashSet<String> keys = new HashSet<>();
            for (int index = 0; index < attributes.size(); index++) {
                assertFalse(attributes.keyAt(index).isBlank());
                assertTrue(keys.add(attributes.keyAt(index)));
            }
            assertEquals(true, attributes.get("logyard.capture.truncated"));
        }
    }

    private static Logger logger(
            AdapterRuntimeAccess runtime,
            LogyardMdcAdapter mdc,
            List<String> contextKeys,
            String name) {
        return new LogyardLoggerFactory(
                runtime,
                new Slf4jEventMapper(mdc, new ContextSnapshotPolicy(contextKeys)))
                .getLogger(name);
    }

    private static LogyardRuntime runtime(EventSink sink) {
        return new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("capture"), List.of()),
                Map.of(),
                Map.of("capture", sink),
                Map.of()));
    }

    private static final class RecordingSink implements EventSink {
        private final List<LogEvent> events = new ArrayList<>();

        @Override
        public void accept(LogEvent event) {
            events.add(event);
        }
    }
}
