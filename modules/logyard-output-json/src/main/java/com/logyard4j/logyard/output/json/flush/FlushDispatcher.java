package com.logyard4j.logyard.output.json.flush;

/** Dispatches due flush I/O away from the shared deadline scheduler. */
@FunctionalInterface
interface FlushDispatcher {
    DispatchedFlush dispatch(Runnable action);

    /** Cancellation and completion boundary for one dispatched flush. */
    interface DispatchedFlush {
        void cancel();

        void awaitCompletion();

        boolean completed();
    }
}
