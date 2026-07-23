package com.zsumz.logyard.slf4j.internal.event;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.MessageFormatter;
import com.zsumz.logyard.api.ingress.IngressMetadata;
import com.zsumz.logyard.slf4j.internal.context.ContextSnapshotPolicy;
import com.zsumz.logyard.slf4j.internal.context.LogyardMdcAdapter;
import com.zsumz.logyard.slf4j.internal.diagnostics.ProviderDiagnostics;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;
import org.slf4j.event.LoggingEvent;

/** Adapter preserving templates, arguments, typed fields, markers, causes, and source metadata. */
public final class Slf4jEventMapper {
    public static final int MAX_KEY_VALUE_PAIRS = 256;
    private static final String EVENT_NAME_KEY = "event.name";

    private final LogyardMdcAdapter mdcAdapter;
    private final ContextSnapshotPolicy contextPolicy;
    private final MarkerCollector markerCollector;

    public Slf4jEventMapper(
            LogyardMdcAdapter mdcAdapter,
            ContextSnapshotPolicy contextPolicy) {
        this(mdcAdapter, contextPolicy, new MarkerCollector());
    }

    Slf4jEventMapper(
            LogyardMdcAdapter mdcAdapter,
            ContextSnapshotPolicy contextPolicy,
            MarkerCollector markerCollector) {
        this.mdcAdapter = Objects.requireNonNull(mdcAdapter, "mdcAdapter");
        this.contextPolicy = Objects.requireNonNull(contextPolicy, "contextPolicy");
        this.markerCollector = Objects.requireNonNull(markerCollector, "markerCollector");
    }

    public void publishNormalized(
            LogyardLogger delegate,
            org.slf4j.event.Level sourceLevel,
            Marker marker,
            String messagePattern,
            Object[] arguments,
            Throwable throwable) {
        Objects.requireNonNull(delegate, "delegate");
        try {
            Level level = LevelMapper.toLogyard(sourceLevel);
            if (!delegate.isEnabled(level)) {
                return;
            }
            AttributeSet.Builder attributes = AttributeSet.builder()
                    .putAll(contextPolicy.capture(mdcAdapter));
            MarkerCollector.Result markers = markerCollector.collect(marker);
            addMarkers(attributes, markers);
            delegate.log(
                    level,
                    null,
                    messagePattern,
                    arguments,
                    attributes.build(),
                    throwable);
            reportMarkerFailure(delegate.name(), markers);
        } catch (Throwable failure) {
            ProviderDiagnostics.rethrowIfFatal(failure);
            ProviderDiagnostics.eventMappingFailure(delegate.name(), failure);
        }
    }

    public void publish(LogyardLogger delegate, LoggingEvent event) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(event, "event");
        Capture capture = new Capture();
        try {
            org.slf4j.event.Level sourceLevel = read(event::getLevel, null, capture);
            if (sourceLevel == null) {
                reportCaptureFailure(delegate.name(), capture);
                return;
            }
            Level level = LevelMapper.toLogyard(sourceLevel);
            if (!delegate.isEnabled(level)) {
                return;
            }

            AttributeSet.Builder attributes = AttributeSet.builder()
                    .putAll(contextPolicy.capture(mdcAdapter));
            String eventName = addKeyValues(
                    attributes,
                    read(event::getKeyValuePairs, null, capture),
                    capture);
            MarkerCollector.Result markers = markerCollector.collect(
                    read(event::getMarkers, null, capture));
            addMarkers(attributes, markers);
            capture.merge(markers.captureFailures(), markers.firstFailure());

            String message = read(event::getMessage, null, capture);
            Object[] arguments = read(event::getArgumentArray, null, capture);
            Throwable throwable = read(event::getThrowable, null, capture);
            Long timestamp = read(event::getTimeStamp, null, capture);
            String threadName = read(event::getThreadName, null, capture);
            if (capture.failures > 0) {
                attributes.put("logyard.slf4j.capture_failures", capture.failures);
            }
            IngressMetadata metadata = timestamp == null
                    ? IngressMetadata.current()
                    : IngressMetadata.source(timestamp, threadName);
            delegate.log(
                    level,
                    eventName,
                    message,
                    arguments,
                    attributes.build(),
                    throwable,
                    metadata);
            reportCaptureFailure(delegate.name(), capture);
        } catch (Throwable failure) {
            ProviderDiagnostics.rethrowIfFatal(failure);
            ProviderDiagnostics.eventMappingFailure(delegate.name(), failure);
        }
    }

    private static String addKeyValues(
            AttributeSet.Builder attributes,
            List<KeyValuePair> pairs,
            Capture capture) {
        if (pairs == null) {
            return null;
        }
        Iterator<KeyValuePair> iterator;
        try {
            iterator = pairs.iterator();
        } catch (Throwable failure) {
            capture.record(failure);
            return null;
        }
        String eventName = null;
        int visited = 0;
        int invalid = 0;
        boolean truncated = false;
        try {
            while (visited < MAX_KEY_VALUE_PAIRS && iterator.hasNext()) {
                KeyValuePair pair = iterator.next();
                visited++;
                if (pair == null || pair.key == null || pair.key.isBlank()) {
                    invalid++;
                    continue;
                }
                if (EVENT_NAME_KEY.equals(pair.key)) {
                    if (pair.value != null) {
                        eventName = MessageFormatter.safeToString(pair.value);
                    }
                } else if (!attributes.isFull()) {
                    attributes.put(pair.key, pair.value);
                } else {
                    truncated = true;
                }
            }
            truncated |= iterator.hasNext();
        } catch (Throwable failure) {
            capture.record(failure);
        }
        if (invalid > 0) {
            attributes.put("logyard.slf4j.invalid_key_values", invalid);
        }
        if (truncated) {
            attributes.put("logyard.slf4j.key_values.truncated", true);
        }
        return eventName;
    }

    private static void addMarkers(
            AttributeSet.Builder attributes,
            MarkerCollector.Result markers) {
        List<String> names = markers.names();
        if (names.size() == 1) {
            attributes.put("slf4j.marker", names.getFirst());
        } else if (!names.isEmpty()) {
            attributes.put("slf4j.markers", names);
        }
        if (markers.truncated()) {
            attributes.put("logyard.slf4j.markers.truncated", true);
        }
        if (markers.captureFailures() > 0) {
            attributes.put("logyard.slf4j.marker_capture_failures", markers.captureFailures());
        }
    }

    private static void reportMarkerFailure(
            String loggerName,
            MarkerCollector.Result markers) {
        if (markers.firstFailure() != null) {
            ProviderDiagnostics.captureFailure(loggerName, markers.firstFailure());
        }
    }

    private static void reportCaptureFailure(String loggerName, Capture capture) {
        if (capture.firstFailure != null) {
            ProviderDiagnostics.captureFailure(loggerName, capture.firstFailure);
        }
    }

    private static <T> T read(Supplier<T> source, T fallback, Capture capture) {
        try {
            return source.get();
        } catch (Throwable failure) {
            capture.record(failure);
            return fallback;
        }
    }

    private static final class Capture {
        private int failures;
        private Throwable firstFailure;

        private void record(Throwable failure) {
            ProviderDiagnostics.rethrowIfFatal(failure);
            failures++;
            if (firstFailure == null) {
                firstFailure = failure;
            }
        }

        private void merge(int count, Throwable failure) {
            failures += count;
            if (firstFailure == null) {
                firstFailure = failure;
            }
        }
    }
}
