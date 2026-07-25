package com.zsumz.logyard.api.event;

import java.text.ChoiceFormat;
import java.text.FieldPosition;
import java.text.Format;
import java.text.MessageFormat;
import java.text.ParsePosition;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Plans the exact {@link MessageFormat} branches that can execute and bounds their construction work. */
final class MessageFormatWorkBudget {
    private MessageFormatWorkBudget() {
    }

    static Analysis analyze(String pattern, int parameterCount, int capturedParameterCount) {
        Locale locale = Locale.getDefault(Locale.Category.FORMAT);
        Analysis analysis = new Analysis(Plan.parse(pattern, locale, true), parameterCount, capturedParameterCount);
        analysis.root.markReferences(analysis);
        return analysis;
    }

    static final class Analysis {
        private final Plan root;
        private final int parameterCount;
        private final boolean[] referenced;
        private boolean referencedParameterOmitted;
        private boolean nestedChoice;

        private Analysis(Plan root, int parameterCount, int capturedParameterCount) {
            this.root = root;
            this.parameterCount = parameterCount;
            referenced = new boolean[capturedParameterCount];
        }

        boolean referenced(int index) {
            return referenced[index];
        }

        boolean referencedParameterOmitted() {
            return referencedParameterOmitted;
        }

        boolean resolveChoices(Object[] captured) {
            root.resolveChoices(this, captured, false);
            return !nestedChoice;
        }

        boolean permits(Object[] captured) {
            return !nestedChoice && root.maximumWork(captured) <= CaptureLimits.MAX_FORMATTER_WORK_CHARS;
        }

        String render(Object[] captured) {
            return root.render(captured);
        }

        private void mark(int argumentIndex) {
            if (argumentIndex < 0) {
                return;
            }
            if (argumentIndex < referenced.length) {
                referenced[argumentIndex] = true;
            } else if (argumentIndex < parameterCount) {
                referencedParameterOmitted = true;
            }
        }
    }

    private static final class Plan {
        private final String pattern;
        private final Locale locale;
        private final ZoneId zone;
        private final List<Element> elements;
        private final MessageFormat executable;

        private Plan(String pattern, Locale locale, ZoneId zone, List<Element> elements, MessageFormat executable) {
            this.pattern = pattern;
            this.locale = locale;
            this.zone = zone;
            this.elements = elements;
            this.executable = executable;
        }

        private static Plan parse(String pattern, Locale locale, boolean validateWithMessageFormat) {
            List<MessageFormatPattern.Element> seeds = MessageFormatPattern.parse(pattern);
            ZoneId zone = MessageFormatPattern.containsDateTime(seeds)
                    ? TrustedFormattingZone.defaultZone()
                    : null;
            MessageFormat messageFormat =
                    validateWithMessageFormat ? executable(pattern, seeds, locale, zone) : null;
            Format[] formats = messageFormat == null ? null : messageFormat.getFormats();
            List<Element> elements = new ArrayList<>(seeds.size());
            for (int index = 0; index < seeds.size(); index++) {
                MessageFormatPattern.Element seed = seeds.get(index);
                Format format = formats == null ? plannedFormat(seed, locale, zone) : formats[index];
                elements.add(new Element(seed, format));
            }
            return new Plan(pattern, locale, zone, List.copyOf(elements), messageFormat);
        }

        private static MessageFormat executable(
                String pattern,
                List<MessageFormatPattern.Element> seeds,
                Locale locale,
                ZoneId zone) {
            MessageFormat messageFormat =
                    new MessageFormat(MessageFormatPattern.sanitizeDateTime(pattern, seeds), locale);
            Format[] formats = messageFormat.getFormats();
            if (seeds.size() != formats.length) {
                throw new IllegalArgumentException("MessageFormat element plan did not match the parsed pattern");
            }
            for (int index = 0; index < formats.length; index++) {
                MessageFormatPattern.Element seed = seeds.get(index);
                if (seed.dateTime()) {
                    formats[index] = new TrustedDateTimeFormat(
                            seed.formatType(),
                            seed.formatStyle(),
                            locale,
                            zone);
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

        private void markReferences(Analysis analysis) {
            for (Element element : elements) {
                analysis.mark(element.seed.argumentIndex());
            }
        }

        private void resolveChoices(Analysis analysis, Object[] captured, boolean recursiveChoice) {
            for (Element element : elements) {
                if (!(element.format instanceof ChoiceFormat choice)) {
                    continue;
                }
                if (recursiveChoice) {
                    analysis.nestedChoice = true;
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
                Plan selectedPlan = parse(selectedPattern, locale, false);
                element.selected = SelectedChoice.plan(selectedPlan);
                selectedPlan.resolveChoices(analysis, captured, true);
                if (analysis.nestedChoice) {
                    return;
                }
                selectedPlan.markReferences(analysis);
            }
        }

        private long maximumWork(Object[] captured) {
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

        private String render(Object[] captured) {
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
                        && captured[element.seed.argumentIndex()] != null
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

    private record SelectedChoice(String literal, Plan plan) {
        private static SelectedChoice literal(String value) {
            return new SelectedChoice(value, null);
        }

        private static SelectedChoice plan(Plan value) {
            return new SelectedChoice(null, value);
        }

        private long maximumWork(Object[] captured) {
            return plan == null ? literal.length() : plan.maximumWork(captured);
        }

        private String render(Object[] captured) {
            return plan == null ? literal : plan.render(captured);
        }
    }

    private static final class LiteralFormat extends Format {
        private static final long serialVersionUID = 1L;

        private final String value;

        private LiteralFormat(String value) {
            this.value = value;
        }

        @Override
        public StringBuffer format(Object ignored, StringBuffer target, FieldPosition position) {
            return target.append(value);
        }

        @Override
        public Object parseObject(String source, ParsePosition position) {
            position.setErrorIndex(position.getIndex());
            return null;
        }
    }

}
