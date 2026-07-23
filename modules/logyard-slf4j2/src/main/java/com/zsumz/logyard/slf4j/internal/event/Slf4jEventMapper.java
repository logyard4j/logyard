package com.zsumz.logyard.slf4j.internal.event;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.ingress.IngressMetadata;
import com.zsumz.logyard.api.ingress.LogEventIngress;
import com.zsumz.logyard.slf4j.internal.context.ContextSnapshotPolicy;
import com.zsumz.logyard.slf4j.internal.context.LogyardMdcAdapter;
import com.zsumz.logyard.slf4j.internal.diagnostics.ProviderDiagnostics;

import java.util.Objects;
import org.slf4j.Marker;
import org.slf4j.event.LoggingEvent;

/** Adapter preserving templates, arguments, typed fields, markers, causes, and source metadata. */
public final class Slf4jEventMapper {
    public static final int MAX_KEY_VALUE_PAIRS = Slf4jKeyValueCollector.MAX_PAIRS;

    private final Slf4jAttributeMapper attributeMapper;

    public Slf4jEventMapper(
            LogyardMdcAdapter mdcAdapter,
            ContextSnapshotPolicy contextPolicy) {
        this(mdcAdapter, contextPolicy, new MarkerCollector());
    }

    Slf4jEventMapper(
            LogyardMdcAdapter mdcAdapter,
            ContextSnapshotPolicy contextPolicy,
            MarkerCollector markerCollector) {
        attributeMapper = new Slf4jAttributeMapper(mdcAdapter, contextPolicy, markerCollector);
    }

    public void publishNormalized(
            LogEventIngress delegate,
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
            AttributeSet.Builder attributes = attributeMapper.contextAttributes();
            MarkerCollector.Result markers = attributeMapper.addMarkers(attributes, marker);
            delegate.log(
                    level,
                    null,
                    messagePattern,
                    arguments,
                    attributes.build(),
                    throwable);
            attributeMapper.reportMarkerFailure(delegate.name(), markers);
        } catch (Throwable failure) {
            ProviderDiagnostics.rethrowIfFatal(failure);
            ProviderDiagnostics.eventMappingFailure(delegate.name(), failure);
        }
    }

    public void publish(LogEventIngress delegate, LoggingEvent event) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(event, "event");
        Slf4jCaptureFailures capture = new Slf4jCaptureFailures();
        try {
            org.slf4j.event.Level sourceLevel = capture.read(event::getLevel, null);
            if (sourceLevel == null) {
                capture.report(delegate.name());
                return;
            }
            Level level = LevelMapper.toLogyard(sourceLevel);
            if (!delegate.isEnabled(level)) {
                return;
            }

            AttributeSet.Builder attributes = attributeMapper.contextAttributes();
            String eventName = attributeMapper.addKeyValues(
                    attributes,
                    capture.read(event::getKeyValuePairs, null),
                    capture);
            MarkerCollector.Result markers = attributeMapper.addMarkers(
                    attributes,
                    capture.read(event::getMarkers, null));
            capture.merge(markers.captureFailures(), markers.firstFailure());

            String message = capture.read(event::getMessage, null);
            Object[] arguments = capture.read(event::getArgumentArray, null);
            Throwable throwable = capture.read(event::getThrowable, null);
            Long timestamp = capture.read(event::getTimeStamp, null);
            String threadName = capture.read(event::getThreadName, null);
            capture.annotate(attributes);
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
            capture.report(delegate.name());
        } catch (Throwable failure) {
            ProviderDiagnostics.rethrowIfFatal(failure);
            ProviderDiagnostics.eventMappingFailure(delegate.name(), failure);
        }
    }
}
