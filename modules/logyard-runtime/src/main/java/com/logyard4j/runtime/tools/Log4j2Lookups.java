package com.logyard4j.runtime.tools;

import java.util.Locale;

/**
 * Rewrites Log4j2 property references and lookups into Logyard environment expressions.
 *
 * <p>Declared {@code <Property>} values are substituted first. What remains is a Log4j
 * lookup: {@code ${env:VAR}} and {@code ${env:VAR:-default}} become Logyard's
 * {@code ${VAR}} and {@code ${VAR:-default}}, and an unprefixed {@code ${NAME}} is left
 * alone because both tools resolve it against the environment. Every other lookup prefix is
 * left in place verbatim and reported: Logyard cannot evaluate it, so a human has to choose
 * the replacement, and the emitted document says so rather than guessing.</p>
 */
final class Log4j2Lookups {
    private Log4j2Lookups() {
    }

    static String resolve(String value, LogbackModel model, String context) {
        if (value == null) {
            return null;
        }
        String substituted = model.substitute(value);
        if (!substituted.contains("${")) {
            return substituted;
        }
        StringBuilder result = new StringBuilder(substituted.length());
        int cursor = 0;
        while (cursor < substituted.length()) {
            int opening = substituted.indexOf("${", cursor);
            int closing = opening < 0 ? -1 : substituted.indexOf('}', opening + 2);
            if (opening < 0 || closing < 0) {
                result.append(substituted, cursor, substituted.length());
                break;
            }
            result.append(substituted, cursor, opening)
                    .append(expression(substituted.substring(opening + 2, closing), model, context));
            cursor = closing + 1;
        }
        return result.toString();
    }

    private static String expression(String expression, LogbackModel model, String context) {
        int prefixEnd = prefixEnd(expression);
        if (prefixEnd < 0) {
            return "${" + expression + "}";
        }
        String prefix = expression.substring(0, prefixEnd).toLowerCase(Locale.ROOT);
        if (prefix.equals("env")) {
            return "${" + expression.substring(prefixEnd + 1) + "}";
        }
        model.note(context + ": the " + prefix + " lookup '${" + expression + "}' has no Logyard equivalent"
                + " and was left in place; replace it with ${ENV_VAR} or a literal value");
        return "${" + expression + "}";
    }

    /** Returns the index of the lookup prefix separator, or -1 when the colon opens a default. */
    private static int prefixEnd(String expression) {
        int colon = expression.indexOf(':');
        boolean opensDefault = colon + 1 < expression.length() && expression.charAt(colon + 1) == '-';
        return colon <= 0 || opensDefault ? -1 : colon;
    }
}
