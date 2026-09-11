package com.logyard4j.api.event;

import java.text.ChoiceFormat;
import java.text.Format;
import java.text.MessageFormat;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Immutable parse plan for the bounded {@link MessageFormat} execution path. */
final class MessageFormatExecutionPlan {
    private final String pattern;
    private final Locale locale;
    private final ZoneId zone;
    private final List<Element> elements;
    private final MessageFormat executable;

    private MessageFormatExecutionPlan(
            String pattern,
            Locale locale,
            ZoneId zone,
            List<Element> elements,
            MessageFormat executable) {
        this.pattern = pattern;
        this.locale = locale;
        this.zone = zone;
        this.elements = elements;
        this.executable = executable;
    }

    static MessageFormatExecutionPlan parse(String pattern, Locale locale, boolean validateWithMessageFormat) {
        List<MessageFormatPattern.Element> seeds = MessageFormatPattern.parse(pattern);
        ZoneId zone = MessageFormatPattern.containsDateTime(seeds) ? TrustedFormattingZone.defaultZone() : null;
        MessageFormat messageFormat = validateWithMessageFormat ? executable(pattern, seeds, locale, zone) : null;
        Format[] formats = messageFormat == null ? null : messageFormat.getFormats();
        List<Element> elements = new ArrayList<>(seeds.size());
        for (int index = 0; index < seeds.size(); index++) {
            MessageFormatPattern.Element seed = seeds.get(index);
            Format format = formats == null ? plannedFormat(seed, locale, zone) : formats[index];
            elements.add(new Element(seed, format));
        }
        return new MessageFormatExecutionPlan(pattern, locale, zone, List.copyOf(elements), messageFormat);
    }

    void markReferences(MessageFormatWorkBudget.Analysis analysis) {
        for (Element element : elements) {
            analysis.mark(element.seed.argumentIndex());
        }
    }

    void resolveChoices(MessageFormatWorkBudget.Analysis analysis, Object[] captured, boolean recursiveChoice) {
        for (Element element : elements) {
            if (!(element.format instanceof ChoiceFormat choice)) {
                continue;
            }
            if (recursiveChoice) {
                analysis.markNestedChoice();
                return;
            }
            if (element.seed.argumentIndex() < 0 || element.seed.argumentIndex() >= captured.length) {
                continue;
            }
            Object selector = captured[element.seed.argumentIndex()];
            if (selector == null) {
                element.selected = SelectedChoice.literal("null");
                continue;
            }
            String selectedPattern = choice.format(selector);
            if (selectedPattern.indexOf('{') < 0) {
                element.selected = SelectedChoice.literal(selectedPattern);
                continue;
            }
            MessageFormatExecutionPlan selectedPlan = parse(selectedPattern, locale, false);
            element.selected = SelectedChoice.plan(selectedPlan);
            selectedPlan.resolveChoices(analysis, captured, true);
            if (analysis.nestedChoice()) {
                return;
            }
            selectedPlan.markReferences(analysis);
        }
    }

    long maximumWork(Object[] captured) {
        long work = pattern.length();
        for (Element element : elements) {
            if (element.seed.argumentIndex() < 0 || element.seed.argumentIndex() >= captured.length) {
                continue;
            }
            long expansion = element.maximumWork(captured);
            if (expansion > CaptureLimits.MAX_FORMATTER_WORK_CHARS - work) {
                return CaptureLimits.MAX_FORMATTER_WORK_CHARS + 1L;
            }
            work += expansion;
        }
        return work;
    }

    String render(Object[] captured) {
        MessageFormat formatter = executable == null
                ? executable(pattern, elements.stream().map(element -> element.seed).toList(), locale, zone)
                : executable;
        Format[] formats = formatter.getFormats();
        ZoneId effectiveZone = zone;
        for (int index = 0; index < elements.size(); index++) {
            Element element = elements.get(index);
            if (element.format instanceof ChoiceFormat && element.selected != null) {
                formats[index] = new LiteralFormat(element.selected.render(captured));
            } else if (element.format == null
                    && element.seed.argumentIndex() >= 0
                    && element.seed.argumentIndex() < captured.length
                    && captured[element.seed.argumentIndex()] instanceof CapturedTemporal) {
                if (effectiveZone == null) {
                    effectiveZone = TrustedFormattingZone.defaultZone();
                }
                formats[index] = new TrustedDateTimeFormat("datetime", "short", locale, effectiveZone);
            }
        }
        formatter.setFormats(formats);
        return formatter.format(captured);
    }

    private static MessageFormat executable(
            String pattern,
            List<MessageFormatPattern.Element> seeds,
            Locale locale,
            ZoneId zone) {
        MessageFormat messageFormat = new MessageFormat(MessageFormatPattern.sanitizeDateTime(pattern, seeds), locale);
        Format[] formats = messageFormat.getFormats();
        if (seeds.size() != formats.length) {
            throw new IllegalArgumentException("MessageFormat element plan did not match the parsed pattern");
        }
        for (int index = 0; index < formats.length; index++) {
            MessageFormatPattern.Element seed = seeds.get(index);
            if (seed.dateTime()) {
                formats[index] = new TrustedDateTimeFormat(seed.formatType(), seed.formatStyle(), locale, zone);
            }
        }
        messageFormat.setFormats(formats);
        return messageFormat;
    }

    private static Format plannedFormat(MessageFormatPattern.Element seed, Locale locale, ZoneId zone) {
        return seed.dateTime()
                ? new TrustedDateTimeFormat(seed.formatType(), seed.formatStyle(), locale, zone)
                : seed.choiceFormat();
    }

    private static final class Element {
        private final MessageFormatPattern.Element seed;
        private final Format format;
        private SelectedChoice selected;

        private Element(MessageFormatPattern.Element seed, Format format) {
            this.seed = seed;
            this.format = format;
        }

        private long maximumWork(Object[] captured) {
            Object value = captured[seed.argumentIndex()];
            if (format instanceof ChoiceFormat) {
                if (value == null) {
                    return 4L;
                }
                if (selected == null) {
                    throw new IllegalArgumentException("ChoiceFormat branch was not resolved");
                }
                return selected.maximumWork(captured);
            }
            return FormattedValueWorkBudget.maximum(value, format, seed.sourceCharacters());
        }
    }

    private record SelectedChoice(String literal, MessageFormatExecutionPlan plan) {
        private static SelectedChoice literal(String value) {
            return new SelectedChoice(value, null);
        }

        private static SelectedChoice plan(MessageFormatExecutionPlan value) {
            return new SelectedChoice(null, value);
        }

        private long maximumWork(Object[] captured) {
            return plan == null ? literal.length() : plan.maximumWork(captured);
        }

        private String render(Object[] captured) {
            return plan == null ? literal : plan.render(captured);
        }
    }
}
