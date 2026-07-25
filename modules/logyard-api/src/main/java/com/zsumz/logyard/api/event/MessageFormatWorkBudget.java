package com.zsumz.logyard.api.event;

import java.text.ChoiceFormat;
import java.text.Format;
import java.text.MessageFormat;
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
        private final List<Element> elements;

        private Plan(String pattern, Locale locale, List<Element> elements) {
            this.pattern = pattern;
            this.locale = locale;
            this.elements = elements;
        }

        private static Plan parse(String pattern, Locale locale, boolean validateWithMessageFormat) {
            List<MessageFormatPattern.Element> seeds = MessageFormatPattern.parse(pattern);
            Format[] formats = null;
            if (validateWithMessageFormat) {
                MessageFormat messageFormat = new MessageFormat(pattern, locale);
                formats = messageFormat.getFormats();
                if (seeds.size() != formats.length) {
                    throw new IllegalArgumentException("MessageFormat element plan did not match the parsed pattern");
                }
            }
            List<Element> elements = new ArrayList<>(seeds.size());
            for (int index = 0; index < seeds.size(); index++) {
                MessageFormatPattern.Element seed = seeds.get(index);
                Format format = formats == null
                        ? seed.choiceFormat()
                        : formats[index];
                elements.add(new Element(seed.argumentIndex(), seed.sourceCharacters(), format));
            }
            return new Plan(pattern, locale, List.copyOf(elements));
        }

        private void markReferences(Analysis analysis) {
            for (Element element : elements) {
                analysis.mark(element.argumentIndex);
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
                if (element.argumentIndex < 0 || element.argumentIndex >= captured.length) {
                    continue;
                }
                Object selector = captured[element.argumentIndex];
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
                if (element.argumentIndex < 0 || element.argumentIndex >= captured.length) {
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
    }

    private static final class Element {
        private final int argumentIndex;
        private final int sourceCharacters;
        private final Format format;
        private SelectedChoice selected;

        private Element(int argumentIndex, int sourceCharacters, Format format) {
            this.argumentIndex = argumentIndex;
            this.sourceCharacters = sourceCharacters;
            this.format = format;
        }

        private long maximumWork(Object[] captured) {
            Object value = captured[argumentIndex];
            if (format instanceof ChoiceFormat) {
                if (value == null) {
                    return 4L;
                }
                if (selected == null) {
                    throw new IllegalArgumentException("ChoiceFormat branch was not resolved");
                }
                return selected.maximumWork(captured);
            }
            return FormattedValueWorkBudget.maximum(value, format, sourceCharacters);
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
    }

}
