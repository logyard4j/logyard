package com.zsumz.logyard.api.delivery;

/**
 * Caller-visible behavior when a bounded asynchronous output cannot accept an event.
 */
public enum OverflowAction {
    /** Discard the event and increment the output's drop counters. */
    DROP,

    /** Wait for bounded capacity, then write the emergency representation to standard error. */
    BLOCK,

    /** Wait for bounded capacity, then discard; never deliver the event on the publishing thread. */
    WAIT_DROP,

    /** Deliver directly on the publishing thread. */
    SYNC,

    /** Emit a bounded emergency representation directly to standard error. */
    STDERR
}
