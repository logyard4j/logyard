package com.zsumz.logyard.output.json.file;

import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.diagnostics.HealthStatus;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Maps a coherent writer snapshot into the public JSON-file health contract. */
final class JsonFileHealth {
    private JsonFileHealth() {
    }

    static ComponentHealth component(
            String componentName,
            Path path,
            boolean rotating,
            boolean sinkClosed,
            WriterHealthSnapshot snapshot) {
        String writerFailure = snapshot.writerFailureType();
        String maintenanceFailure = snapshot.maintenanceFailureType();
        HealthStatus status;
        if (sinkClosed || snapshot.writerState().equals("CLOSED")) {
            status = HealthStatus.STOPPED;
        } else if (writerFailure != null || maintenanceFailure != null || !snapshot.maintenanceWorkerAlive()) {
            status = HealthStatus.FAILED;
        } else if (snapshot.maintenanceClosing()) {
            status = HealthStatus.STOPPING;
        } else if (snapshot.maintenanceQueueCapacity() > 0
                && snapshot.maintenanceQueuedTasks() * 4L >= snapshot.maintenanceQueueCapacity() * 3L) {
            status = HealthStatus.DEGRADED;
        } else {
            status = HealthStatus.HEALTHY;
        }

        Map<String, String> details = new LinkedHashMap<>();
        details.put("format", "jsonl");
        details.put("path", path.toString());
        details.put("rotation", Boolean.toString(rotating));
        details.put("writer_state", snapshot.writerState().toLowerCase(Locale.ROOT));
        if (writerFailure != null) {
            details.put("writer_failure", writerFailure);
        }
        if (maintenanceFailure != null) {
            details.put("maintenance_failure", maintenanceFailure);
        }
        Map<String, Long> metrics = Map.of(
                "maintenance_queue_capacity", (long) snapshot.maintenanceQueueCapacity(),
                "maintenance_queue_depth", (long) snapshot.maintenanceQueuedTasks());
        return new ComponentHealth(componentName, "json-file-output", status, details, metrics);
    }
}
