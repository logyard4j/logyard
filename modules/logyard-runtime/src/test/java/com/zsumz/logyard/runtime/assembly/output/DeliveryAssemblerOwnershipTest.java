package com.zsumz.logyard.runtime.assembly.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.config.ProviderConfiguration;
import com.zsumz.logyard.api.spi.output.BatchEventSink;
import com.zsumz.logyard.config.delivery.DeliveryConfig;
import com.zsumz.logyard.config.delivery.DeliveryOverrideConfig;
import com.zsumz.logyard.config.extension.ProviderReferenceConfig;
import com.zsumz.logyard.config.output.CustomOutputConfig;
import com.zsumz.logyard.core.failure.ComponentInvocationException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class DeliveryAssemblerOwnershipTest {
    private static final CustomOutputConfig OUTPUT = new CustomOutputConfig(
            "custom",
            Level.TRACE,
            new ProviderReferenceConfig("test", null, ProviderConfiguration.EMPTY),
            null,
            null,
            DeliveryOverrideConfig.INHERIT);
    private static final DeliveryConfig DELIVERY =
            new DeliveryConfig("async", DeliveryConfig.MIN_CAPACITY, Map.of());

    @Test
    void invalidBatchSizeClosesTheRawProviderExactlyOnce() {
        ConfigurableBatchSink raw = new ConfigurableBatchSink(0, Duration.ZERO);

        assertThrows(IllegalArgumentException.class, () -> wrap(raw));

        assertEquals(1, raw.closeCalls.get());
    }

    @Test
    void invalidAndNullBatchDelaysCloseTheRawProvider() {
        ConfigurableBatchSink invalid = new ConfigurableBatchSink(1, Duration.ofMinutes(2));
        ConfigurableBatchSink missing = new ConfigurableBatchSink(1, null);

        assertThrows(IllegalArgumentException.class, () -> wrap(invalid));
        assertThrows(IllegalArgumentException.class, () -> wrap(missing));

        assertEquals(1, invalid.closeCalls.get());
        assertEquals(1, missing.closeCalls.get());
    }

    @Test
    void hostileBatchPolicyCallbacksAreIsolatedAndCloseTheProvider() {
        AssertionError sizeFailure = new AssertionError("size policy boom");
        ThrowingBatchSink size = ThrowingBatchSink.maximumSize(sizeFailure);
        AssertionError delayFailure = new AssertionError("delay policy boom");
        ThrowingBatchSink delay = ThrowingBatchSink.maximumDelay(delayFailure);

        ComponentInvocationException isolatedSize =
                assertThrows(ComponentInvocationException.class, () -> wrap(size));
        ComponentInvocationException isolatedDelay =
                assertThrows(ComponentInvocationException.class, () -> wrap(delay));

        assertSame(sizeFailure, isolatedSize.getCause());
        assertSame(delayFailure, isolatedDelay.getCause());
        assertEquals(1, size.closeCalls.get());
        assertEquals(1, delay.closeCalls.get());
    }

    @Test
    void laterDecoratorFailureStopsTheStartedWorkerAndClosesItsDelegate() {
        ConfigurableBatchSink raw = new ConfigurableBatchSink(16, Duration.ZERO);
        IllegalStateException decorationFailure = new IllegalStateException("decoration failed");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> DeliveryAssembler.wrap(
                        OUTPUT,
                        raw,
                        DELIVERY,
                        Duration.ofSeconds(1),
                        ignored -> {
                            throw decorationFailure;
                        }));

        assertSame(decorationFailure, thrown);
        assertEquals(1, raw.closeCalls.get());
        assertFalse(threadAlive("logyard-output-custom"));
    }

    @Test
    void cleanupFailureIsSuppressedWithoutReplacingTheConstructionFailure() {
        IllegalStateException cleanupFailure = new IllegalStateException("cleanup failed");
        ConfigurableBatchSink raw = new ConfigurableBatchSink(0, Duration.ZERO, cleanupFailure);

        IllegalArgumentException constructionFailure =
                assertThrows(IllegalArgumentException.class, () -> wrap(raw));

        assertEquals(1, constructionFailure.getSuppressed().length);
        assertSame(cleanupFailure, constructionFailure.getSuppressed()[0]);
        assertEquals(1, raw.closeCalls.get());
    }

    private static void wrap(BatchEventSink raw) {
        DeliveryAssembler.wrap(OUTPUT, raw, DELIVERY, Duration.ofSeconds(1));
    }

    private static boolean threadAlive(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().equals(name));
    }

    private static class ConfigurableBatchSink implements BatchEventSink {
        private final int maximumSize;
        private final Duration maximumDelay;
        private final RuntimeException closeFailure;
        final AtomicInteger closeCalls = new AtomicInteger();

        ConfigurableBatchSink(int maximumSize, Duration maximumDelay) {
            this(maximumSize, maximumDelay, null);
        }

        ConfigurableBatchSink(int maximumSize, Duration maximumDelay, RuntimeException closeFailure) {
            this.maximumSize = maximumSize;
            this.maximumDelay = maximumDelay;
            this.closeFailure = closeFailure;
        }

        @Override
        public int maximumBatchSize() {
            return maximumSize;
        }

        @Override
        public Duration maximumBatchDelay() {
            return maximumDelay;
        }

        @Override
        public void acceptBatch(List<LogEvent> events) {
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class ThrowingBatchSink extends ConfigurableBatchSink {
        private final Error sizeFailure;
        private final Error delayFailure;

        private ThrowingBatchSink(Error sizeFailure, Error delayFailure) {
            super(16, Duration.ZERO);
            this.sizeFailure = sizeFailure;
            this.delayFailure = delayFailure;
        }

        static ThrowingBatchSink maximumSize(Error failure) {
            return new ThrowingBatchSink(failure, null);
        }

        static ThrowingBatchSink maximumDelay(Error failure) {
            return new ThrowingBatchSink(null, failure);
        }

        @Override
        public int maximumBatchSize() {
            if (sizeFailure != null) {
                throw sizeFailure;
            }
            return super.maximumBatchSize();
        }

        @Override
        public Duration maximumBatchDelay() {
            if (delayFailure != null) {
                throw delayFailure;
            }
            return super.maximumBatchDelay();
        }
    }
}
