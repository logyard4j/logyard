package com.zsumz.logyard.slf4j.internal.event;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.slf4j.internal.context.ContextSnapshotPolicy;
import com.zsumz.logyard.slf4j.internal.context.LogyardMdcAdapter;
import com.zsumz.logyard.slf4j.internal.diagnostics.ProviderDiagnostics;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;

import java.util.List;
import java.util.Objects;

final class Slf4jAttributeMapper {
    private final LogyardMdcAdapter mdcAdapter;
    private final ContextSnapshotPolicy contextPolicy;
    private final MarkerCollector markerCollector;
    private final Slf4jKeyValueCollector keyValueCollector;

    Slf4jAttributeMapper(
            LogyardMdcAdapter mdcAdapter,
            ContextSnapshotPolicy contextPolicy,
            MarkerCollector markerCollector) {
        this.mdcAdapter = Objects.requireNonNull(mdcAdapter, "mdcAdapter");
        this.contextPolicy = Objects.requireNonNull(contextPolicy, "contextPolicy");
        this.markerCollector = Objects.requireNonNull(markerCollector, "markerCollector");
        keyValueCollector = new Slf4jKeyValueCollector();
    }

    AttributeSet.Builder contextAttributes() {
        return AttributeSet.builder().putAll(contextPolicy.capture(mdcAdapter));
    }

    String addKeyValues(AttributeSet.Builder attributes, List<KeyValuePair> pairs, Slf4jCaptureFailures failures) {
        return keyValueCollector.collect(attributes, pairs, failures);
    }

    MarkerCollector.Result addMarkers(AttributeSet.Builder attributes, Marker marker) {
        return addMarkers(attributes, markerCollector.collect(marker));
    }

    MarkerCollector.Result addMarkers(AttributeSet.Builder attributes, List<Marker> markers) {
        return addMarkers(attributes, markerCollector.collect(markers));
    }

    void reportMarkerFailure(String loggerName, MarkerCollector.Result markers) {
        if (markers.firstFailure() != null) {
            ProviderDiagnostics.captureFailure(loggerName, markers.firstFailure());
        }
    }

    private static MarkerCollector.Result addMarkers(AttributeSet.Builder attributes, MarkerCollector.Result markers) {
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
        return markers;
    }
}
