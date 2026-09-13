package com.logyard4j.logyard.runtime.tools;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the Log4j2 {@code <Loggers>} section onto Logyard logger rules.
 *
 * <p>{@code <AsyncRoot>} and {@code <AsyncLogger>} are ordinary rules here: Logyard hands
 * every event to an asynchronous per-output pipeline, so asynchrony is not a property of
 * the logger.</p>
 */
final class Log4j2Loggers {
    private Log4j2Loggers() {
    }

    static void convert(Element root, LogbackModel model) {
        for (Element container : Log4j2Xml.children(root, "Loggers")) {
            for (Element element : Log4j2Xml.elements(container)) {
                declaration(element, model);
            }
        }
    }

    private static void declaration(Element element, LogbackModel model) {
        String tag = element.getTagName();
        String kind = tag.regionMatches(true, 0, "Async", 0, 5) ? tag.substring(5) : tag;
        boolean isRoot = kind.equalsIgnoreCase("Root");
        if (!isRoot && !kind.equalsIgnoreCase("Logger")) {
            model.note("<" + tag + "> inside <Loggers> has no Logyard equivalent and was ignored");
            return;
        }
        if (!kind.equals(tag)) {
            model.delivery.put("mode", "async");
            model.note("<" + tag + "> was treated as <" + kind + ">: Logyard delivery is asynchronous per"
                    + " output, so there is no separate asynchronous logger");
        }
        String name = isRoot ? "root" : Log4j2Xml.attribute(element, "name");
        if (name == null) {
            model.note("a <" + tag + "> without a name attribute was ignored");
            return;
        }
        Map<String, Object> rule = rule(element, model, isRoot ? "root logger" : "logger '" + name + "'", isRoot);
        if (!rule.isEmpty()) {
            model.loggers.put(name, rule);
        }
    }

    private static Map<String, Object> rule(Element logger, LogbackModel model, String context, boolean isRoot) {
        Map<String, Object> rule = new LinkedHashMap<>();
        String level = Log4j2Lookups.resolve(Log4j2Xml.attribute(logger, "level"), model, context);
        List<String> outputs = references(logger, model, context);
        boolean additive = !"false".equalsIgnoreCase(Log4j2Xml.attribute(logger, "additivity"));
        if (!outputs.isEmpty() || isRoot || !additive) {
            rule.put("outputs", outputs);
            if (!outputs.isEmpty() && !isRoot && additive) {
                model.note(context + ": Logyard logger outputs replace inherited outputs instead of adding"
                        + " to them (Log4j2 additivity)");
            }
        }
        Log4j2Filters.report(context, logger, model);
        LogbackLevels.loggerRule(level, rule, model, context);
        return rule;
    }

    private static List<String> references(Element logger, LogbackModel model, String context) {
        List<String> outputs = new ArrayList<>();
        for (Element reference : Log4j2Xml.children(logger, "AppenderRef")) {
            String name = Log4j2Xml.attribute(reference, "ref");
            String resolved = name == null ? null : model.resolveOutput(name);
            if (resolved == null) {
                model.note(context + ": AppenderRef '" + name + "' does not resolve to a converted output"
                        + " and was dropped");
            } else if (!outputs.contains(resolved)) {
                outputs.add(resolved);
            }
            if (Log4j2Xml.attribute(reference, "level") != null) {
                model.note(context + ": the level on AppenderRef '" + name + "' was dropped;"
                        + " set min_level on the output instead");
            }
            Log4j2Filters.report(context + " AppenderRef '" + name + "'", reference, model);
        }
        return outputs;
    }
}
