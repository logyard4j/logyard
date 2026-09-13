package com.logyard4j.logyard.output.json.file;

/** Last-known writer state with independently sampled maintenance progress. */
record WriterHealthSnapshot(
        String writerState,
        String writerFailureType,
        boolean maintenanceWorkerAlive,
        boolean maintenanceClosing,
        String maintenanceFailureType,
        int maintenanceQueueCapacity,
        int maintenanceQueuedTasks) {
}
