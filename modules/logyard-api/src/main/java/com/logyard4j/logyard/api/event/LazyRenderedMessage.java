package com.logyard4j.logyard.api.event;

/** Thread-safe one-time rendering of a captured template and its detached arguments. */
final class LazyRenderedMessage {
    private final String template;
    private final Object[] arguments;
    private volatile MessageFormatter.RenderResult result;

    private LazyRenderedMessage(String template, Object[] arguments) {
        this.template = template;
        this.arguments = arguments;
    }

    /** No renderer is needed when the captured template is already the complete bounded result. */
    static LazyRenderedMessage forEvent(String template, Object[] arguments) {
        int literalLength = template == null ? 4 : template.length();
        if ((template == null || arguments.length == 0) && literalLength <= CaptureLimits.MAX_RENDERED_MESSAGE_CHARS) {
            return null;
        }
        return new LazyRenderedMessage(template, arguments);
    }

    String value() {
        return result().value();
    }

    boolean truncated() {
        return result().truncated();
    }

    private MessageFormatter.RenderResult result() {
        MessageFormatter.RenderResult current = result;
        if (current == null) {
            synchronized (this) {
                current = result;
                if (current == null) {
                    current = MessageFormatter.formatResult(template, arguments, CaptureLimits.MAX_RENDERED_MESSAGE_CHARS);
                    result = current;
                }
            }
        }
        return current;
    }
}
