package com.zsumz.logyard.core.delivery.async;

import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.diagnostics.HealthStatus;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.api.spi.diagnostics.HealthContributor;
import com.zsumz.logyard.core.diagnostics.EmergencyText;

import java.util.LinkedHashMap;
import java.util.Map;

/** Assembles a bounded health view without participating in delivery coordination. */
final class AsyncSinkHealth {
    private AsyncSinkHealth() {
    }

    static ComponentHealth snapshot(String componentName, EventSink delegate, State state) {
        HealthStatus status = status(state);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("delivery", "async");
        details.put("accepting", Boolean.toString(state.accepting()));
        details.put("worker_alive", Boolean.toString(state.workerAlive()));
        details.put("delegate", delegate.getClass().getName());
        details.put("caller_thread_delivery", Boolean.toString(state.callerThreadDeliveryAllowed()));
        details.put("batching", Boolean.toString(state.batching()));

        if (delegate instanceof HealthContributor contributor) {
            try {
                ComponentHealth delegateHealth = contributor.health(componentName + ".delegate");
                status = HealthStatus.worst(status, delegateHealth.status());
                details.put("delegate_status", delegateHealth.status().name().toLowerCase(java.util.Locale.ROOT));
            } catch (RuntimeException failure) {
                status = HealthStatus.FAILED;
                details.put("delegate_status", "failed");
                details.put("delegate_health_failure", EmergencyText.failureSummary(failure, 512));
            }
        }

        Map<String, Long> measurements = new LinkedHashMap<>();
        measurements.put("capacity", (long) state.capacity());
        measurements.put("queued", (long) state.queued());
        measurements.put("active_deliveries", (long) state.activeDeliveries());
        measurements.put("outstanding_queued_events", (long) state.outstandingQueuedEvents());
        measurements.put("maximum_batch_size", (long) state.maximumBatchSize());
        measurements.put("enqueued_total", state.telemetry().enqueued());
        measurements.put("delivered_total", state.telemetry().delivered());
        measurements.put("dropped_total", state.telemetry().dropped());
        measurements.put("synchronous_fallback_total", state.telemetry().synchronousFallbacks());
        measurements.put("emergency_fallback_total", state.telemetry().emergencyFallbacks());
        return new ComponentHealth(componentName, "output", status, details, measurements);
    }

    private static HealthStatus status(State state) {
        if (!state.workerAlive() && state.running()) {
            return HealthStatus.FAILED;
        }
        if (state.delegateCloseStarted()) {
            return state.workerAlive() ? HealthStatus.STOPPING : HealthStatus.STOPPED;
        }
        if (!state.accepting()) {
            return HealthStatus.STOPPING;
        }
        if (state.telemetry().dropped() > 0L || state.telemetry().emergencyFallbacks() > 0L || state.queued() * 5L >= state.capacity() * 4L) {
            return HealthStatus.DEGRADED;
        }
        return HealthStatus.HEALTHY;
    }

    record State(
            boolean running,
            boolean accepting,
            boolean workerAlive,
            boolean delegateCloseStarted,
            boolean callerThreadDeliveryAllowed,
            boolean batching,
            int capacity,
            int queued,
            int activeDeliveries,
            int outstandingQueuedEvents,
            int maximumBatchSize,
            AsyncSinkMetrics.Snapshot telemetry) {
    }
}
