package com.zsumz.logyard.core.failure;

/** Recoverable component failure converted to the runtime's ordinary exception channel. */
public final class ComponentInvocationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String component;

    ComponentInvocationException(String component, Throwable cause) {
        super("Logyard component invocation failed: " + component, cause);
        this.component = component;
    }

    public String component() {
        return component;
    }
}
