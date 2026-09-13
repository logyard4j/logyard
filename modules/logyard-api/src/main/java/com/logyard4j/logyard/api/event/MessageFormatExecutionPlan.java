package com.logyard4j.logyard.api.event;

import java.text.ChoiceFormat;
import java.text.Format;
import java.text.MessageFormat;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/** Owns the parsed elements, formats, and choices for one bounded {@link MessageFormat} render. */
final class MessageFormatExecutionPlan {
    private final String pattern;
    private final Locale locale;
    private final ZoneId zone;
    private final List<MessageFormatPattern.Element> elements;
    private final Format[] formats;
    private final MessageFormat executable;
    private SelectedChoice[] selected;

    private MessageFormatExecutionPlan(
            String pattern,
            Locale locale,
            ZoneId zone,
            List<MessageFormatPattern.Element> elements,
            Format[] formats,
            MessageFormat executable) {
        this.pattern = pattern;
        this.locale = locale;
        this.zone = zone;
        this.elements = elements;
        this.formats = formats;
        this.executable = executable;
    }

    static MessageFormatExecutionPlan parse(String pattern, Locale locale, boolean validateWithMessageFormat) {
        List<MessageFormatPattern.Element> seeds = MessageFormatPattern.parse(pattern);
        ZoneId zone = MessageFormatPattern.containsDateTime(seeds) ? TrustedFormattingZone.defaultZone() : null;
        MessageFormat messageFormat = validateWithMessageFormat ? executable(pattern, seeds, locale, zone) : null;
        Format[] formats = messageFormat == null ? new Format[seeds.size()] : messageFormat.getFormats();
        if (messageFormat == null) {
            for (int index = 0; index < seeds.size(); index++) {
                formats[index] = plannedFormat(seeds.get(index), locale, zone);
            }
        }
        return new MessageFormatExecutionPlan(pattern, locale, zone, seeds, formats, messageFormat);
    }

    void markReferences(MessageFormatWorkBudget.Analysis analysis) {
        for (MessageFormatPattern.Element element : elements) {
            analysis.mark(element.argumentIndex());
        }
    }

    void resolveChoices(MessageFormatWorkBudget.Analysis analysis, Object[] captured, boolean recursiveChoice) {
        for (int index = 0; index < elements.size(); index++) {
            if (!(formats[index] instanceof ChoiceFormat choice)) {
                continue;
            }
            if (recursiveChoice) {
                analysis.markNestedChoice();
                return;
            }
            MessageFormatPattern.Element element = elements.get(index);
            if (element.argumentIndex() < 0 || element.argumentIndex() >= captured.length) {
                continue;
            }
            if (selected == null) {
                selected = new SelectedChoice[elements.size()];
            }
            Object selector = captured[element.argumentIndex()];
            if (selector == null) {
                selected[index] = SelectedChoice.literal("null");
                continue;
            }
            String selectedPattern = choice.format(selector);
            if (selectedPattern.indexOf('{') < 0) {
                selected[index] = SelectedChoice.literal(selectedPattern);
                continue;
            }
            MessageFormatExecutionPlan selectedPlan = parse(selectedPattern, locale, false);
            selected[index] = SelectedChoice.plan(selectedPlan);
            selectedPlan.resolveChoices(analysis, captured, true);
            if (analysis.nestedChoice()) {
                return;
            }
            selectedPlan.markReferences(analysis);
        }
    }

    long maximumWork(Object[] captured) {
        long work = pattern.length();
        for (int index = 0; index < elements.size(); index++) {
            MessageFormatPattern.Element element = elements.get(index);
            if (element.argumentIndex() < 0 || element.argumentIndex() >= captured.length) {
                continue;
            }
            long expansion = maximumWork(index, captured);
            if (expansion > CaptureLimits.MAX_FORMATTER_WORK_CHARS - work) {
                return CaptureLimits.MAX_FORMATTER_WORK_CHARS + 1L;
            }
            work += expansion;
        }
        return work;
    }

    String render(Object[] captured) {
        MessageFormat formatter = executable == null
                ? executable(pattern, elements, locale, zone)
                : executable;
        Format[] renderedFormats = formatter.getFormats();
        ZoneId effectiveZone = zone;
        for (int index = 0; index < elements.size(); index++) {
            MessageFormatPattern.Element element = elements.get(index);
            if (formats[index] instanceof ChoiceFormat && selected != null && selected[index] != null) {
                renderedFormats[index] = new LiteralFormat(selected[index].render(captured));
            } else if (formats[index] == null
                    && element.argumentIndex() >= 0
                    && element.argumentIndex() < captured.length
                    && captured[element.argumentIndex()] instanceof CapturedTemporal) {
                if (effectiveZone == null) {
                    effectiveZone = TrustedFormattingZone.defaultZone();
                }
                renderedFormats[index] = new TrustedDateTimeFormat("datetime", "short", locale, effectiveZone);
            }
        }
        formatter.setFormats(renderedFormats);
        return formatter.format(captured);
    }

    private long maximumWork(int index, Object[] captured) {
        MessageFormatPattern.Element element = elements.get(index);
        Object value = captured[element.argumentIndex()];
        if (formats[index] instanceof ChoiceFormat) {
            if (value == null) {
                return 4L;
            }
            SelectedChoice selection = selected == null ? null : selected[index];
            if (selection == null) {
                throw new IllegalArgumentException("ChoiceFormat branch was not resolved");
            }
            return selection.maximumWork(captured);
        }
        return FormattedValueWorkBudget.maximum(value, formats[index], element.sourceCharacters());
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
