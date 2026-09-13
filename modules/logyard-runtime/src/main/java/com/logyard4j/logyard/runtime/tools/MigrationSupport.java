package com.logyard4j.logyard.runtime.tools;

import org.w3c.dom.Element;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Conservative XML vocabulary audit: an unexamined construct cannot yield an exact result. */
final class MigrationSupport {
    private record Rule(Set<String> attributes, Set<String> children) { }

    private static final Map<String, Rule> LOGBACK = Map.ofEntries(
            entry("configuration", "scan scanPeriod", "appender root logger property variable contextName springProfile"),
            entry("appender", "name class", "target encoder filter"),
            entry("encoder", "class", "pattern"),
            entry("target", "", ""), entry("pattern", "", ""), entry("contextName", "", ""),
            entry("root", "level", "appender-ref"), entry("logger", "name level additivity", "appender-ref"),
            entry("appender-ref", "ref", ""), entry("property", "name value", ""), entry("variable", "name value", ""),
            entry("filter", "class", "level"), entry("level", "", ""));
    private static final Map<String, Rule> LOG4J2 = Map.ofEntries(
            entry("configuration", "name status monitorInterval packages", "appenders loggers properties"),
            entry("appenders", "", "console"), entry("console", "name target", "patternlayout thresholdfilter"),
            entry("patternlayout", "pattern", "pattern"), entry("pattern", "", ""),
            entry("loggers", "", "root logger"), entry("root", "level", "appenderref thresholdfilter"),
            entry("logger", "name level additivity", "appenderref thresholdfilter"),
            entry("appenderref", "ref level", "thresholdfilter"),
            entry("properties", "", "property"), entry("property", "name value", ""),
            entry("thresholdfilter", "level onMatch onMismatch", ""));

    private MigrationSupport() { }

    static void audit(Element root, LogbackModel model, boolean logback) {
        inspect(root, model, logback, new HashSet<>());
        List<Element> roots = logback ? LogbackXml.children(root, "root") : Log4j2Xml.children(root, "Loggers")
                .stream().flatMap(loggers -> Log4j2Xml.children(loggers, "Root").stream()).toList();
        if (roots.size() != 1 || !roots.getFirst().hasAttribute("level")) {
            model.unsupported("an explicit root logger and level are required for exact migration");
        }
    }

    private static void inspect(Element element, LogbackModel model, boolean logback, Set<String> names) {
        String tag = name(element, logback);
        Rule rule = (logback ? LOGBACK : LOG4J2).get(tag);
        if (rule == null) {
            model.unsupported("<" + element.getTagName() + "> is outside the exact migration subset; review the draft");
        } else {
            var attributes = element.getAttributes();
            for (int index = 0; index < attributes.getLength(); index++) {
                String attribute = attributes.item(index).getNodeName();
                if (!rule.attributes().contains(attribute)) {
                    model.unsupported("<" + element.getTagName() + "> attribute '" + attribute + "' was not converted");
                }
            }
            for (Element child : Log4j2Xml.elements(element)) {
                if (!rule.children().contains(name(child, logback))) {
                    model.unsupported("<" + child.getTagName() + "> inside <" + element.getTagName()
                            + "> is outside the exact migration subset");
                }
            }
        }
        if (tag.equals(logback ? "appender" : "console")) {
            String original = element.getAttribute("name");
            if (original.isBlank() || !names.add(original)) {
                model.unsupported("missing or duplicate appender name '" + original + "'");
            }
            Element layout = logback ? LogbackXml.child(element, "encoder") : Log4j2Xml.child(element, "PatternLayout");
            if (layout == null) model.unsupported("appender '" + original + "' requires an explicit supported pattern layout");
        }
        if (logback && element.hasAttribute("class")) {
            String type = element.getAttribute("class");
            String expected = switch (tag) {
                case "appender" -> "ch.qos.logback.core.ConsoleAppender";
                case "encoder" -> "ch.qos.logback.classic.encoder.PatternLayoutEncoder";
                case "filter" -> "ch.qos.logback.classic.filter.ThresholdFilter";
                default -> "";
            };
            if (!type.equals(expected)) model.unsupported("class '" + type + "' is outside the exact migration subset");
        }
        for (Element child : Log4j2Xml.elements(element)) inspect(child, model, logback, names);
    }

    private static String name(Element element, boolean logback) {
        return logback ? element.getTagName() : element.getTagName().toLowerCase(Locale.ROOT);
    }

    private static Map.Entry<String, Rule> entry(String name, String attributes, String children) {
        return Map.entry(name, new Rule(words(attributes), words(children)));
    }

    private static Set<String> words(String value) {
        return value.isEmpty() ? Set.of() : Set.of(value.split(" "));
    }
}
