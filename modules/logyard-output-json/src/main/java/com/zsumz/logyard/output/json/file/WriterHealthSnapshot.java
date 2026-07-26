package com.zsumz.logyard.output.json.file;

/** Immutable, coherently published operational state for one rotating writer. */
record WriterHealthSnapshot(
        String writerState,
        String writerFailureType,
        boolean maintenanceWorkerAlive,
        boolean maintenanceClosing,
        String maintenanceFailureType,
        int maintenanceQueueCapacity,
        int maintenanceQueuedTasks) {
}
