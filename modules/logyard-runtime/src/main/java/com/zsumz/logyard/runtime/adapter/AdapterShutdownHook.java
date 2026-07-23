package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.runtime.diagnostics.AdapterDiagnostics;

import java.util.concurrent.atomic.AtomicBoolean;

final class AdapterShutdownHook implements ShutdownHookRegistrar {
    private static final int MAX_THREAD_COMPONENT_LENGTH = 48;

    private final AtomicBoolean installed = new AtomicBoolean();

    @Override
    public void install(String adapterName, Runnable shutdown) {
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(shutdown, "logyard-" + threadComponent(adapterName) + "-shutdown"));
        } catch (IllegalStateException | SecurityException failure) {
            installed.set(false);
            AdapterDiagnostics.shutdownHookFailure(failure);
        }
    }

    private static String threadComponent(String value) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_THREAD_COMPONENT_LENGTH));
        for (int index = 0; index < value.length() && result.length() < MAX_THREAD_COMPONENT_LENGTH; index++) {
            char character = Character.toLowerCase(value.charAt(index));
            result.append(Character.isLetterOrDigit(character) ? character : '-');
        }
        return result.toString();
    }
}
