package com.logyard4j.logyard.runtime.tools;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps Log4j2 filter elements onto Logyard output thresholds and reports the rest.
 *
 * <p>Only a {@code ThresholdFilter} that accepts at or above a level and denies everything
 * else is expressible as {@code min_level}; any other match/mismatch combination, and every
 * other filter, is reported instead of being approximated.</p>
 */
final class Log4j2Filters {
    private Log4j2Filters() {
    }

    /** Returns the filters declared on an element, unwrapping a {@code <Filters>} composite. */
    static List<Element> of(Element parent) {
        List<Element> filters = new ArrayList<>();
        for (Element element : Log4j2Xml.elements(parent)) {
            if (Log4j2Xml.named(element, "Filters")) {
                filters.addAll(Log4j2Xml.elements(element));
            } else if (element.getTagName().toLowerCase(Locale.ROOT).endsWith("filter")) {
                filters.add(element);
            }
        }
        return filters;
    }

    /** Applies appender filters to one output, reporting whatever a threshold cannot express. */
    static void apply(String context, Element appender, Map<String, Object> output, LogbackModel model) {
        List<Element> filters = of(appender);
        if (filters.size() > 1) {
            model.note(context + ": composite filters were not converted; preserve their ordered"
                    + " accept/deny semantics manually");
            return;
        }
        for (Element filter : filters) {
            if (Log4j2Xml.named(filter, "ThresholdFilter")) {
                threshold(context, filter, output, model);
            } else {
                model.note(context + ": " + describe(filter.getTagName()));
            }
        }
    }

    /** Reports filters attached where Logyard has no per-scope equivalent at all. */
    static void report(String context, Element parent, LogbackModel model) {
        for (Element filter : of(parent)) {
            model.note(context + ": " + describe(filter.getTagName()));
        }
    }

    private static String describe(String tag) {
        if (tag.equalsIgnoreCase("BurstFilter")) {
            return "<BurstFilter> has no direct equivalent; declare a [filters.*] rate_limit rule"
                    + " and attach it to the logger with filters = [...]";
        }
        if (tag.equalsIgnoreCase("ThresholdFilter") || tag.equalsIgnoreCase("LevelRangeFilter")) {
            return "<" + tag + "> applies here to a scope Logyard does not filter;"
                    + " set min_level on the output instead";
        }
        return "<" + tag + "> has no Logyard equivalent and was dropped";
    }

    private static void threshold(String context, Element filter, Map<String, Object> output, LogbackModel model) {
        String onMatch = decision(filter, "onMatch", "NEUTRAL");
        String onMismatch = decision(filter, "onMismatch", "DENY");
        if (!onMismatch.equalsIgnoreCase("DENY")
                || !(onMatch.equalsIgnoreCase("NEUTRAL") || onMatch.equalsIgnoreCase("ACCEPT"))) {
            model.note(context + ": <ThresholdFilter onMatch=\"" + onMatch + "\" onMismatch=\"" + onMismatch
                    + "\"> is not a plain threshold and was not converted");
            return;
        }
        String level = Log4j2Lookups.resolve(Log4j2Xml.attribute(filter, "level"), model, context);
        if ("OFF".equalsIgnoreCase(level)) {
            model.note(context + ": an OFF appender threshold requires removing its output routes manually");
            return;
        }
        // Log4j 2's ThresholdFilter.createFilter uses ERROR when no level is supplied.
        output.put("min_level", LogbackLevels.map(level == null ? "ERROR" : level, model, context));
    }

    private static String decision(Element filter, String name, String fallback) {
        String value = Log4j2Xml.attribute(filter, name);
        return value == null ? fallback : value;
    }
}
