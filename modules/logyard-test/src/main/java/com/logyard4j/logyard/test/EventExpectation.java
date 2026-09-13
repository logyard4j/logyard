package com.logyard4j.logyard.test;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.event.LogEvent;

import java.util.List;
import java.util.Objects;

/**
 * Composable expectation over recorded events; every criterion added must hold for an event to match.
 *
 * <p>A builder starts with no criteria, so it matches every recorded event. Criteria are conjunctive
 * and may be combined freely; repeating {@link #attribute(String, Object)} for different keys
 * requires all of them, while repeating a single-valued criterion such as {@link #level(Level)}
 * replaces the previous value. A builder is mutable and thread-confined. Each assertion reads a fresh snapshot;
 * start an independent expectation with {@link RecordedEvents#expect()}.</p>
 *
 * <p>Each terminal reads one immutable snapshot of the recorder, and on failure throws an
 * {@link AssertionError} naming the criteria and dumping at most 20 recorded events, one line each,
 * with rendered messages truncated to 120 characters.</p>
 */
public final class EventExpectation {
    private final RecordedEvents events;
    private final EventCriteria criteria = new EventCriteria();

    EventExpectation(RecordedEvents events) {
        this.events = events;
    }

    /**
     * Requires an exact event level.
     *
     * @param value required level
     * @return this expectation
     */
    public EventExpectation level(Level value) {
        criteria.level(Objects.requireNonNull(value, "value"));
        return this;
    }

    /**
     * Requires an exact logger name.
     *
     * @param value required logger name
     * @return this expectation
     */
    public EventExpectation logger(String value) {
        criteria.loggerName(Objects.requireNonNull(value, "value"));
        return this;
    }

    /**
     * Requires an exact stable event name.
     *
     * @param value required event name
     * @return this expectation
     */
    public EventExpectation eventName(String value) {
        criteria.eventName(Objects.requireNonNull(value, "value"));
        return this;
    }

    /**
     * Requires the rendered message to contain a substring.
     *
     * <p>The comparison uses the message a text output would render, so template placeholders are
     * already replaced by their arguments.</p>
     *
     * @param value required substring of the rendered message
     * @return this expectation
     */
    public EventExpectation messageContains(String value) {
        criteria.messageSubstring(Objects.requireNonNull(value, "value"));
        return this;
    }

    /**
     * Requires a structured attribute to be present, whatever its value.
     *
     * <p>A key captured with a {@code null} value counts as present.</p>
     *
     * @param key required attribute key
     * @return this expectation
     */
    public EventExpectation attribute(String key) {
        criteria.attributePresent(Objects.requireNonNull(key, "key"));
        return this;
    }

    /**
     * Requires a structured attribute to be present with an equal value.
     *
     * <p>Values are compared with {@link Objects#equals(Object, Object)} against the captured
     * attribute, so an attribute value's own type matters: a {@code long} captured as {@code 7L}
     * does not equal {@code 7}.</p>
     *
     * @param key required attribute key
     * @param value required attribute value, possibly {@code null}
     * @return this expectation
     */
    public EventExpectation attribute(String key, Object value) {
        criteria.attributeValue(Objects.requireNonNull(key, "key"), value);
        return this;
    }

    /**
     * Requires a captured exception of exactly this type.
     *
     * <p>An event carries a detached snapshot naming the throwable's type, so the match is on the
     * fully qualified type name and a subtype does not satisfy a supertype criterion.</p>
     *
     * @param type required throwable type
     * @return this expectation
     */
    public EventExpectation exceptionType(Class<? extends Throwable> type) {
        criteria.exceptionType(Objects.requireNonNull(type, "type").getName());
        return this;
    }

    /**
     * Asserts that at least one recorded event matches.
     *
     * @throws AssertionError if capture overflowed, or when no recorded event matches
     */
    public void assertPresent() {
        List<LogEvent> snapshot = events.all();
        if (matchCount(snapshot) == 0) {
            throw failure("expected at least one matching event, found none", snapshot);
        }
    }

    /**
     * Asserts an exact number of matching recorded events.
     *
     * @param expected required number of matches
     * @throws IllegalArgumentException when the expected count is negative
     * @throws AssertionError if capture overflowed, or when a different number of recorded events matches
     */
    public void assertCount(int expected) {
        if (expected < 0) {
            throw new IllegalArgumentException("expected match count must not be negative");
        }
        List<LogEvent> snapshot = events.all();
        int actual = matchCount(snapshot);
        if (actual != expected) {
            throw failure("expected exactly " + expected + " matching event(s), found " + actual, snapshot);
        }
    }

    /**
     * Asserts that no recorded event matches.
     *
     * @throws AssertionError if capture overflowed, or when any recorded event matches
     */
    public void assertNone() {
        List<LogEvent> snapshot = events.all();
        int actual = matchCount(snapshot);
        if (actual != 0) {
            throw failure("expected no matching event, found " + actual, snapshot);
        }
    }

    private int matchCount(List<LogEvent> snapshot) {
        int matches = 0;
        for (LogEvent event : snapshot) {
            if (criteria.matches(event)) {
                matches++;
            }
        }
        return matches;
    }

    private AssertionError failure(String problem, List<LogEvent> snapshot) {
        return new AssertionError(problem
                + System.lineSeparator() + "criteria: " + criteria.describe()
                + System.lineSeparator() + CapturedEventDump.render(snapshot));
    }
}
