package com.logyard4j.runtime.tools;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps Log4j2 rolling policies and strategies onto Logyard's {@code rotate} table.
 *
 * <p>Logyard rotates on size and keeps a bounded number of archives, so size policies and
 * the rollover strategy's {@code max} translate directly while every time-driven policy is
 * reported instead of being approximated.</p>
 */
final class Log4j2Rollover {
    private static final Map<String, String> UNITS = Map.of(
            "K", "KiB", "KB", "KiB", "KIB", "KiB",
            "M", "MiB", "MB", "MiB", "MIB", "MiB",
            "G", "GiB", "GB", "GiB", "GIB", "GiB");

    private Log4j2Rollover() {
    }

    static void apply(String name, Element appender, Map<String, Object> output, LogbackModel model) {
        String context = "appender '" + name + "'";
        Map<String, Object> rotate = new LinkedHashMap<>();
        boolean timeBased = policies(appender, rotate, model, context);
        strategy(appender, rotate, model, context);
        timeBased |= filePattern(appender, rotate, model, context);
        if (timeBased) {
            model.note(context + ": time-based rollover was not converted because Log4j derives its period"
                    + " from the %d pattern in filePattern; set outputs." + name.toLowerCase(Locale.ROOT)
                    + ".rotate.interval by hand. " + (rotate.containsKey("size")
                            ? "The size-based rotation was kept."
                            : "No size-based rotation was configured either."));
        }
        if (!rotate.isEmpty()) {
            output.put("rotate", rotate);
        }
    }

    /** Applies every triggering policy and reports whether any of them rolls on time. */
    private static boolean policies(Element appender, Map<String, Object> rotate, LogbackModel model, String context) {
        boolean timeBased = false;
        for (Element policy : triggers(appender)) {
            String tag = policy.getTagName();
            if (tag.equalsIgnoreCase("SizeBasedTriggeringPolicy")) {
                size(Log4j2Lookups.resolve(Log4j2Xml.value(policy, "size"), model, context), rotate, model, context);
            } else if (tag.equalsIgnoreCase("TimeBasedTriggeringPolicy")
                    || tag.equalsIgnoreCase("CronTriggeringPolicy")) {
                timeBased = true;
            } else {
                model.note(context + ": <" + tag + "> has no Logyard equivalent and was dropped");
            }
        }
        return timeBased;
    }

    private static List<Element> triggers(Element appender) {
        List<Element> triggers = new ArrayList<>();
        for (Element element : Log4j2Xml.elements(appender)) {
            if (Log4j2Xml.named(element, "Policies")) {
                triggers.addAll(Log4j2Xml.elements(element));
            } else if (element.getTagName().toLowerCase(Locale.ROOT).endsWith("triggeringpolicy")) {
                triggers.add(element);
            }
        }
        return triggers;
    }

    private static void strategy(Element appender, Map<String, Object> rotate, LogbackModel model, String context) {
        Element strategy = Log4j2Xml.child(appender, "DefaultRolloverStrategy");
        if (strategy == null) {
            if (Log4j2Xml.child(appender, "DirectWriteRolloverStrategy") != null) {
                model.note(context + ": <DirectWriteRolloverStrategy> has no Logyard equivalent;"
                        + " Logyard always writes the active file and renames archives");
            }
            return;
        }
        String max = Log4j2Lookups.resolve(Log4j2Xml.attribute(strategy, "max"), model, context);
        if (max == null) {
            return;
        }
        try {
            rotate.put("keep", Long.parseLong(max.trim()));
        } catch (NumberFormatException ignored) {
            model.note(context + ": rollover strategy max '" + max + "' is not a number and was dropped");
        }
    }

    /** Reads the archive naming pattern for compression and time-driven rollover. */
    private static boolean filePattern(
            Element appender, Map<String, Object> rotate, LogbackModel model, String context) {
        String pattern = Log4j2Lookups.resolve(Log4j2Xml.attribute(appender, "filePattern"), model, context);
        if (pattern == null) {
            return false;
        }
        String lower = pattern.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gz")) {
            rotate.put("compression", "gzip");
        } else if (lower.endsWith(".zip") || lower.endsWith(".bz2") || lower.endsWith(".xz")
                || lower.endsWith(".zst") || lower.endsWith(".deflate")) {
            model.note(context + ": filePattern requests an archive format Logyard does not produce;"
                    + " only gzip compression is available");
        }
        return pattern.contains("%d");
    }

    private static void size(String size, Map<String, Object> rotate, LogbackModel model, String context) {
        if (size == null) {
            return;
        }
        String normalized = normalize(size);
        if (normalized == null) {
            model.note(context + ": rotation size '" + size + "' uses a unit Logyard does not understand;"
                    + " set rotate.size by hand (Logyard accepts bytes, KiB, MiB, and GiB)");
            return;
        }
        rotate.put("size", normalized);
    }

    /** Rewrites Log4j2's binary KB/MB/GB units as the explicit KiB/MiB/GiB Logyard reads. */
    private static String normalize(String size) {
        String trimmed = size.replace(" ", "").toUpperCase(Locale.ROOT);
        int split = 0;
        while (split < trimmed.length()
                && (Character.isDigit(trimmed.charAt(split)) || trimmed.charAt(split) == '.')) {
            split++;
        }
        if (split == 0) {
            return null;
        }
        String unit = trimmed.substring(split);
        if (unit.isEmpty() || unit.equals("B")) {
            return trimmed.substring(0, split);
        }
        String mapped = UNITS.get(unit);
        return mapped == null ? null : trimmed.substring(0, split) + mapped;
    }
}
