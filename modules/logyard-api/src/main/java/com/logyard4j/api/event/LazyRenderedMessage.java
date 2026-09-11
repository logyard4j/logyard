package com.logyard4j.api.event;

/** Thread-safe one-time rendering of a captured template and its detached arguments. */
final class LazyRenderedMessage {
    private final String template;
    private final Object[] arguments;
    private final int maximumCharacters;
    private volatile MessageFormatter.RenderResult result;

    LazyRenderedMessage(String template, Object[] arguments, int maximumCharacters) {
        this.template = template;
        this.arguments = arguments;
        this.maximumCharacters = maximumCharacters;
    }

    String value() {
        return result().value();
    }

    boolean truncated() {
        return result().truncated();
    }

    LazyRenderedMessage rerender(String replacementTemplate, Object[] capturedArguments) {
        return new LazyRenderedMessage(replacementTemplate, capturedArguments, maximumCharacters);
    }

    private MessageFormatter.RenderResult result() {
        MessageFormatter.RenderResult current = result;
        if (current == null) {
            synchronized (this) {
                current = result;
                if (current == null) {
                    current = MessageFormatter.formatResult(template, arguments, maximumCharacters);
                    result = current;
                }
            }
        }
        return current;
    }
}
