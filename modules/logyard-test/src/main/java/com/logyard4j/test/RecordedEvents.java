package com.logyard4j.test;

import com.logyard4j.api.event.LogEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Thread-safe record of the events one {@link LogyardTestKit} captured, in publication order.
 *
 * <p>Delivery into a kit is synchronous, so an event is recorded here before the logging call that
 * published it returns. Events published concurrently from several threads are all recorded; their
 * relative order is the order the kit accepted them.</p>
 *
 * <p>{@link #all()} returns an immutable snapshot rather than a live view, so an assertion never
 * races a concurrent publication. Recorded events are already detached and bounded, so holding a
 * snapshot cannot keep an application object graph alive.</p>
 */
public final class RecordedEvents {
    private final List<LogEvent> captured = new ArrayList<>();

    private final int capacity;
    private CapturePhase phase = CapturePhase.COMPLETE;

    RecordedEvents(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capture capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Returns the number of recorded events.
     *
     * @throws AssertionError if the capture limit was exceeded
     * @return recorded event count
     */
    public int size() {
        synchronized (captured) {
            requireComplete();
            return captured.size();
        }
    }

    /**
     * Returns an immutable snapshot of every recorded event in publication order.
     *
     * @throws AssertionError if the capture limit was exceeded
     * @return immutable snapshot of recorded events
     */
    public List<LogEvent> all() {
        synchronized (captured) {
            requireComplete();
            return List.copyOf(captured);
        }
    }

    /** Discards recorded events and resets overflow, leaving the kit open and recording. */
    public void clear() {
        synchronized (captured) {
            captured.clear();
            phase = CapturePhase.COMPLETE;
        }
    }

    /**
     * Starts one independent expectation over the recorded events.
     *
     * <p>Each call returns a fresh builder; criteria added to one never affect another.</p>
     *
     * @return expectation builder matching every recorded event until criteria are added
     */
    public EventExpectation expect() {
        return new EventExpectation(this);
    }

    void record(LogEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (captured) {
            if (captured.size() < capacity) {
                captured.add(event);
            } else {
                phase = CapturePhase.OVERFLOWED;
            }
        }
    }
    private void requireComplete() {
        if (phase == CapturePhase.OVERFLOWED) {
            throw new AssertionError("capture exceeded " + capacity
                    + " events; increase LogyardTestKit.isolated(capacity) or clear between test phases");
        }
    }

    private enum CapturePhase { COMPLETE, OVERFLOWED }
}
