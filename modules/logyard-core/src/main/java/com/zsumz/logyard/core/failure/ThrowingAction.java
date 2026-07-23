package com.zsumz.logyard.core.failure;

/** Component callback that may use a checked or unchecked failure channel. */
@FunctionalInterface
public interface ThrowingAction {
    void run() throws Throwable;
}
