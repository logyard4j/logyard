package com.logyard4j.logyard.api.event;

/** Small SLF4J-style brace formatter with guarded, bounded object rendering. */
public final class MessageFormatter {
    private MessageFormatter() {
    }

    /**
     * Renders SLF4J-style {@code {}} placeholders with captured arguments.
     *
     * @param template message template, or {@code null}
     * @param arguments positional arguments
     * @return bounded rendered message
     */
    public static String format(String template, Object[] arguments) {
        return formatResult(template, arguments, CaptureLimits.MAX_TEXT_CHARS).value();
    }

    static RenderResult formatResult(String template, Object[] arguments, int maximumCharacters) {
        BoundedText result = new BoundedText(maximumCharacters);
        if (template == null) {
            return result.append("null").result();
        }
        if (arguments == null || arguments.length == 0) {
            return result.append(template).result();
        }

        int cursor = 0;
        int argument = 0;
        SafeValueRenderer.RenderState renderState = new SafeValueRenderer.RenderState();
        while (argument < arguments.length && !result.full()) {
            int placeholder = template.indexOf("{}", cursor);
            if (placeholder < 0) {
                break;
            }
            boolean consumedArgument = appendTemplateSegment(
                    result, template, cursor, placeholder, arguments, argument, renderState);
            if (consumedArgument) {
                argument++;
            }
            cursor = placeholder + 2;
        }
        result.append(template, cursor, template.length());
        return result.result();
    }

    /**
     * Renders an arbitrary value without allowing an ordinary {@code toString()} failure to escape.
     *
     * @param value value to render
     * @return bounded rendered value
     */
    public static String safeToString(Object value) {
        return safeRender(value, CaptureLimits.MAX_TEXT_CHARS).value();
    }

    static RenderResult safeRender(Object value, int maximumCharacters) {
        BoundedText result = new BoundedText(maximumCharacters);
        SafeValueRenderer.append(result, value);
        return result.result();
    }

    private static boolean appendTemplateSegment(
            BoundedText result,
            String template,
            int cursor,
            int placeholder,
            Object[] arguments,
            int argument,
            SafeValueRenderer.RenderState renderState) {
        if (!escapedPlaceholder(template, placeholder)) {
            result.append(template, cursor, placeholder);
            SafeValueRenderer.append(result, arguments[argument], renderState, 0);
            return true;
        }
        if (doublyEscapedPlaceholder(template, placeholder)) {
            result.append(template, cursor, placeholder - 1);
            SafeValueRenderer.append(result, arguments[argument], renderState, 0);
            return true;
        }
        result.append(template, cursor, placeholder - 1).append("{}");
        return false;
    }

    private static boolean escapedPlaceholder(String template, int placeholder) {
        return placeholder > 0 && template.charAt(placeholder - 1) == '\\';
    }

    private static boolean doublyEscapedPlaceholder(String template, int placeholder) {
        return placeholder > 1 && template.charAt(placeholder - 2) == '\\';
    }

    record RenderResult(String value, boolean truncated) {
    }
}
