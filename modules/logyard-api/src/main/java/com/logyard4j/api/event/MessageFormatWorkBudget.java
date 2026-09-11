package com.logyard4j.api.event;

import java.util.Locale;

/** Coordinates the referenced parameters and bounded execution plan for one message-format render. */
final class MessageFormatWorkBudget {
    private MessageFormatWorkBudget() {
    }

    static Analysis analyze(String pattern, int parameterCount, int capturedParameterCount) {
        Locale locale = Locale.getDefault(Locale.Category.FORMAT);
        Analysis analysis = new Analysis(
                MessageFormatExecutionPlan.parse(pattern, locale, true), parameterCount, capturedParameterCount);
        analysis.root.markReferences(analysis);
        return analysis;
    }

    static final class Analysis {
        private final MessageFormatExecutionPlan root;
        private final int parameterCount;
        private final boolean[] referenced;
        private boolean referencedParameterOmitted;
        private boolean nestedChoice;

        private Analysis(MessageFormatExecutionPlan root, int parameterCount, int capturedParameterCount) {
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

        void mark(int argumentIndex) {
            if (argumentIndex < 0) {
                return;
            }
            if (argumentIndex < referenced.length) {
                referenced[argumentIndex] = true;
            } else if (argumentIndex < parameterCount) {
                referencedParameterOmitted = true;
            }
        }

        void markNestedChoice() {
            nestedChoice = true;
        }

        boolean nestedChoice() {
            return nestedChoice;
        }
    }
}
