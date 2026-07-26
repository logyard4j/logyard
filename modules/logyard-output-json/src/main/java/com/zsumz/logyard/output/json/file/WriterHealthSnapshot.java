package com.zsumz.logyard.output.json.file;

/** Immutable, coherently published operational state for one rotating writer. */
record WriterHealthSnapshot(
        boolean closed,
        boolean maintenanceWorkerAlive,
        boolean maintenanceClosing,
        String maintenanceFailureType,
        int maintenanceQueueCapacity,
        int maintenanceQueuedTasks) {
}
