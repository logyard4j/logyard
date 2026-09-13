package com.logyard4j.output.json.file;

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
