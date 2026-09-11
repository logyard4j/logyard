package com.logyard4j.runtime.tools;

import org.w3c.dom.Element;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Maps one Logback appender element onto the Logyard output model. */
final class LogbackAppenders {
    private LogbackAppenders() {
    }

    static void convert(Element appender, LogbackModel model) {
        String name = appender.getAttribute("name");
        String type = appender.getAttribute("class");
        String simpleType = type.substring(type.lastIndexOf('.') + 1);
        switch (simpleType) {
            case "ConsoleAppender" -> console(name, appender, model);
            case "FileAppender", "RollingFileAppender" -> file(name, appender, model);
            case "AsyncAppender" -> async(name, appender, model);
            default -> model.note("appender '" + name + "' (" + (type.isEmpty() ? "unknown type" : type)
                    + ") has no Logyard equivalent and was not converted");
        }
    }

    private static void console(String name, Element appender, LogbackModel model) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("type", "console");
        output.put("color", new LinkedHashMap<>(Map.of("mode", "never")));
        output.put("exception", new LinkedHashMap<>(Map.of("style", "full")));
        String target = model.substitute(LogbackXml.childText(appender, "target"));
        output.put("stream", "System.err".equalsIgnoreCase(target) ? "stderr" : "stdout");
        if (target != null && !target.equalsIgnoreCase("System.out") && !target.equalsIgnoreCase("System.err")) {
            model.unsupported("appender '" + name + "': unsupported console target '" + target + "'; review stdout fallback");
        }
        String pattern = encoderPattern(appender, model);
        if (pattern != null) {
            PatternTranslation translation = LogbackPatternTranslator.translate(pattern);
            String formatterName = model.outputName(name) + "-format";
            model.formatters.put(formatterName, new LinkedHashMap<>(
                    Map.of("type", "template", "template", translation.template())));
            output.put("formatter", formatterName);
            translation.report(name, model);
        } else {
            model.unsupported("appender '" + name + "': a supported explicit pattern is required; review the draft formatter");
        }
        threshold(appender, output, model, name);
        model.outputs.put(model.outputName(name), output);
    }

    private static void file(String name, Element appender, LogbackModel model) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("type", "file");
        String file = model.substitute(LogbackXml.childText(appender, "file"));
        if (file == null) {
            model.note("appender '" + name + "' has no <file>; a placeholder path was generated");
            file = "logs/" + model.outputName(name) + ".jsonl";
        }
        output.put("path", file);
        String append = LogbackXml.childText(appender, "append");
        if (append != null) {
            output.put("append", Boolean.parseBoolean(append));
        }
        if (encoderPattern(appender, model) != null) {
            model.note("appender '" + name + "': Logyard file outputs write structured JSON lines;"
                    + " the pattern layout was not carried over");
        }
        rotation(name, appender, output, model);
        threshold(appender, output, model, name);
        model.outputs.put(model.outputName(name), output);
    }

    private static void rotation(String name, Element appender, Map<String, Object> output, LogbackModel model) {
        Element policy = LogbackXml.child(appender, "rollingPolicy");
        Element trigger = LogbackXml.child(appender, "triggeringPolicy");
        if (policy == null && trigger == null) {
            return;
        }
        Map<String, Object> rotate = new LinkedHashMap<>();
        String size = model.substitute(firstText(policy, trigger, "maxFileSize"));
        if (size != null) {
            rotate.put("size", normalizeSize(size));
        }
        String history = model.substitute(firstText(policy, trigger, "maxHistory"));
        if (history != null) {
            try {
                rotate.put("keep", Long.parseLong(history.trim()));
            } catch (NumberFormatException ignored) {
                model.note("appender '" + name + "': maxHistory '" + history + "' is not a number");
            }
        }
        String filePattern = model.substitute(firstText(policy, trigger, "fileNamePattern"));
        if (filePattern != null && filePattern.endsWith(".gz")) {
            rotate.put("compression", "gzip");
        }
        if (filePattern != null && filePattern.contains("%d")) {
            model.note("appender '" + name + "': time-based rollover has no Logyard equivalent;"
                    + " size-based rotation " + (rotate.containsKey("size") ? "was kept" : "was not configured"));
        }
        if (firstText(policy, trigger, "totalSizeCap") != null) {
            model.note("appender '" + name + "': totalSizeCap has no Logyard equivalent;"
                    + " retention is by archive count (keep)");
        }
        if (!rotate.isEmpty()) {
            output.put("rotate", rotate);
        }
    }

    private static void async(String name, Element appender, LogbackModel model) {
        model.delivery.put("mode", "async");
        List<Element> references = LogbackXml.children(appender, "appender-ref");
        for (Element reference : references) {
            model.appenderAliases.put(name, reference.getAttribute("ref"));
        }
        String queueSize = model.substitute(LogbackXml.childText(appender, "queueSize"));
        if (queueSize != null) {
            try {
                long capacity = Long.parseLong(queueSize.trim());
                long existing = model.delivery.get("capacity") instanceof Long current ? current : 0L;
                model.delivery.put("capacity", Math.max(existing, capacity));
            } catch (NumberFormatException ignored) {
                model.note("async appender '" + name + "': queueSize '" + queueSize + "' is not a number");
            }
        }
        model.note("async appender '" + name + "' was unwrapped: Logyard delivery is asynchronous"
                + " per output with per-level overflow policies");
        if (LogbackXml.childText(appender, "discardingThreshold") != null
                || LogbackXml.childText(appender, "neverBlock") != null) {
            model.note("async appender '" + name + "': configure [delivery.overflow] to choose"
                    + " drop, block, sync, or stderr behavior per level");
        }
    }

    private static void threshold(Element appender, Map<String, Object> output, LogbackModel model, String name) {
        List<Element> filters = LogbackXml.children(appender, "filter");
        if (filters.size() > 1) {
            model.note("appender '" + name + "': composite filters were not converted; review their ordered decisions");
            return;
        }
        for (Element filter : filters) {
            String type = filter.getAttribute("class");
            if (type.endsWith("ThresholdFilter")) {
                String level = model.substitute(LogbackXml.childText(filter, "level"));
                if (level != null) {
                    output.put("min_level", LogbackLevels.map(level, model, "appender '" + name + "'"));
                }
            } else {
                model.note("appender '" + name + "': filter " + type + " has no Logyard equivalent");
            }
        }
    }

    private static String encoderPattern(Element appender, LogbackModel model) {
        Element encoder = LogbackXml.child(appender, "encoder");
        if (encoder == null) {
            return null;
        }
        return model.substitute(LogbackXml.childText(encoder, "pattern"));
    }

    private static String firstText(Element policy, Element trigger, String name) {
        String fromPolicy = policy == null ? null : LogbackXml.childText(policy, name);
        return fromPolicy != null ? fromPolicy : trigger == null ? null : LogbackXml.childText(trigger, name);
    }

    private static String normalizeSize(String size) {
        String trimmed = size.trim().toUpperCase(Locale.ROOT);
        if (trimmed.endsWith("KB")) {
            return trimmed.substring(0, trimmed.length() - 2) + "KiB";
        }
        if (trimmed.endsWith("MB")) {
            return trimmed.substring(0, trimmed.length() - 2) + "MiB";
        }
        if (trimmed.endsWith("GB")) {
            return trimmed.substring(0, trimmed.length() - 2) + "GiB";
        }
        return trimmed;
    }
}
