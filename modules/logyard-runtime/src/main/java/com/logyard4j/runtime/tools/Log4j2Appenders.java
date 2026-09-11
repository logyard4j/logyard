package com.logyard4j.runtime.tools;

import org.w3c.dom.Element;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps one Log4j2 appender element onto the Logyard output model.
 *
 * <p>Log4j2 names the appender type with the element itself, so dispatch is by tag name.
 * Console and file appenders become outputs, {@code <Async>} is unwrapped into an alias
 * plus a delivery capacity, and every remaining appender is reported.</p>
 */
final class Log4j2Appenders {
    private Log4j2Appenders() {
    }

    static void convert(Element appender, LogbackModel model) {
        String type = appender.getTagName();
        String name = Log4j2Xml.attribute(appender, "name");
        if (name == null) {
            model.note("a <" + type + "> appender without a name attribute was ignored");
            return;
        }
        switch (type.toLowerCase(Locale.ROOT)) {
            case "console" -> console(name, appender, model);
            case "file", "rollingfile", "randomaccessfile",
                 "rollingrandomaccessfile", "memorymappedfile" -> file(name, appender, model);
            case "async" -> async(name, appender, model);
            default -> model.note("appender '" + name + "' (<" + type + ">) has no Logyard equivalent"
                    + " and was not converted");
        }
    }

    private static void console(String name, Element appender, LogbackModel model) {
        String context = "appender '" + name + "'";
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("type", "console");
        output.put("color", new LinkedHashMap<>(Map.of("mode", "never")));
        output.put("exception", new LinkedHashMap<>(Map.of("style", "full")));
        String target = Log4j2Xml.attribute(appender, "target");
        output.put("stream", "SYSTEM_ERR".equals(target) ? "stderr" : "stdout");
        if (target != null && !target.equals("SYSTEM_OUT") && !target.equals("SYSTEM_ERR")) {
            model.unsupported(context + ": unsupported console target '" + target + "'; review stdout fallback");
        }
        template(name, appender, output, model, context);
        layouts(context, appender, model, true);
        Log4j2Filters.apply(context, appender, output, model);
        register(name, output, model);
    }

    private static void file(String name, Element appender, LogbackModel model) {
        String context = "appender '" + name + "'";
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("type", "file");
        String path = Log4j2Lookups.resolve(Log4j2Xml.attribute(appender, "fileName"), model, context);
        if (path == null) {
            model.note(context + " has no fileName; a placeholder path was generated");
            path = "logs/" + model.outputName(name) + ".jsonl";
        }
        output.put("path", path);
        String append = Log4j2Xml.attribute(appender, "append");
        if (append != null) {
            output.put("append", Boolean.parseBoolean(append));
        }
        layouts(context, appender, model, false);
        Log4j2Rollover.apply(name, appender, output, model);
        Log4j2Filters.apply(context, appender, output, model);
        register(name, output, model);
    }

    private static void async(String name, Element appender, LogbackModel model) {
        model.delivery.put("mode", "async");
        List<Element> references = Log4j2Xml.children(appender, "AppenderRef");
        for (Element reference : references) {
            model.appenderAliases.put(name, Log4j2Xml.attribute(reference, "ref"));
        }
        if (references.size() > 1) {
            model.note("async appender '" + name + "' wraps several appenders; a reference to it resolves to"
                    + " the last one — list the target outputs on the logger instead");
        }
        capacity(name, Log4j2Xml.attribute(appender, "bufferSize"), model);
        model.note("async appender '" + name + "' was unwrapped: Logyard delivery is asynchronous per output"
                + " with per-level overflow policies");
        if (Log4j2Xml.attribute(appender, "blocking") != null) {
            model.note("async appender '" + name + "': configure [delivery.overflow] to choose drop, block,"
                    + " sync, or stderr behavior per level");
        }
    }

    /** Converts a PatternLayout into a template formatter bound to this output. */
    private static void template(
            String name, Element appender, Map<String, Object> output, LogbackModel model, String context) {
        Element layout = Log4j2Xml.child(appender, "PatternLayout");
        String pattern = layout == null
                ? null
                : Log4j2Lookups.resolve(Log4j2Xml.value(layout, "pattern"), model, context);
        if (pattern == null) {
            model.unsupported(context + ": a supported explicit pattern is required; review the draft formatter");
            return;
        }
        PatternTranslation translation = Log4j2PatternTranslator.translate(pattern);
        String formatter = model.outputName(name) + "-format";
        model.formatters.put(formatter, new LinkedHashMap<>(
                Map.of("type", "template", "template", translation.template())));
        output.put("formatter", formatter);
        translation.report(name, model);
    }

    /** Reports every layout that did not become a template formatter. */
    private static void layouts(String context, Element appender, LogbackModel model, boolean textual) {
        for (Element element : Log4j2Xml.elements(appender)) {
            String tag = element.getTagName();
            if (!tag.toLowerCase(Locale.ROOT).endsWith("layout")
                    || (textual && tag.equalsIgnoreCase("PatternLayout"))) {
                continue;
            }
            model.note(context + ": <" + tag + "> was dropped; " + (textual
                    ? "Logyard console output renders through [formatters] and themes"
                    : "Logyard file output already writes structured JSON lines,"
                            + " shaped by [json_profiles] rather than a layout"));
        }
    }

    private static void capacity(String name, String bufferSize, LogbackModel model) {
        if (bufferSize == null) {
            return;
        }
        try {
            long capacity = Long.parseLong(bufferSize.trim());
            long existing = model.delivery.get("capacity") instanceof Long current ? current : 0L;
            model.delivery.put("capacity", Math.max(existing, capacity));
        } catch (NumberFormatException ignored) {
            model.note("async appender '" + name + "': bufferSize '" + bufferSize + "' is not a number");
        }
    }

    private static void register(String name, Map<String, Object> output, LogbackModel model) {
        model.outputs.put(model.outputName(name), output);
    }
}
