package com.logyard4j.test;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.ExceptionSnapshot;
import com.logyard4j.api.event.LogEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Mutable conjunction of event criteria: every criterion added must hold for an event to match. */
final class EventCriteria {
    private final List<AttributeCriterion> attributes = new ArrayList<>();
    private Level level;
    private String loggerName;
    private String eventName;
    private String messageSubstring;
    private String exceptionType;

    void level(Level value) {
        level = value;
    }

    void loggerName(String value) {
        loggerName = value;
    }

    void eventName(String value) {
        eventName = value;
    }

    void messageSubstring(String value) {
        messageSubstring = value;
    }

    void exceptionType(String value) {
        exceptionType = value;
    }

    void attributePresent(String key) {
        attributes.add(new AttributeCriterion(key, false, null));
    }

    void attributeValue(String key, Object value) {
        attributes.add(new AttributeCriterion(key, true, value));
    }

    boolean matches(LogEvent event) {
        if (level != null && event.level() != level) {
            return false;
        }
        if (loggerName != null && !loggerName.equals(event.loggerName())) {
            return false;
        }
        if (eventName != null && !eventName.equals(event.eventName())) {
            return false;
        }
        if (messageSubstring != null && !event.renderedMessage().contains(messageSubstring)) {
            return false;
        }
        if (exceptionType != null && !matchesException(event.exception())) {
            return false;
        }
        return attributes.isEmpty() || matchesAttributes(event.attributes().toMap());
    }

    /** Renders bounded, terminal-safe criteria for assertion diagnostics. */
    String describe() {
        List<String> parts = new ArrayList<>();
        if (level != null) {
            parts.add("level=" + level);
        }
        if (loggerName != null) {
            parts.add("logger=" + CapturedEventDump.text(loggerName));
        }
        if (eventName != null) {
            parts.add("eventName=" + CapturedEventDump.text(eventName));
        }
        if (messageSubstring != null) {
            parts.add("messageContains=\"" + CapturedEventDump.text(messageSubstring) + '"');
        }
        if (exceptionType != null) {
            parts.add("exceptionType=" + CapturedEventDump.text(exceptionType));
        }
        for (int index = 0; index < Math.min(attributes.size(), 20); index++) {
            parts.add(attributes.get(index).describe());
        }
        if (attributes.size() > 20) parts.add("further attribute criteria omitted");
        return parts.isEmpty() ? "any recorded event" : String.join(", ", parts);
    }

    private boolean matchesException(ExceptionSnapshot exception) {
        return exception != null && exceptionType.equals(exception.type());
    }

    private boolean matchesAttributes(Map<String, Object> values) {
        for (AttributeCriterion criterion : attributes) {
            if (!criterion.matches(values)) {
                return false;
            }
        }
        return true;
    }

    private record AttributeCriterion(String key, boolean valueChecked, Object value) {
        private AttributeCriterion {
            Objects.requireNonNull(key, "key");
        }

        boolean matches(Map<String, Object> values) {
            if (!values.containsKey(key)) {
                return false;
            }
            return !valueChecked || Objects.equals(values.get(key), value);
        }

        String describe() {
            String name = "attribute[" + CapturedEventDump.text(key) + "]";
            return valueChecked ? name + "=" + CapturedEventDump.text(String.valueOf(value)) : name + " present";
        }
    }
}
