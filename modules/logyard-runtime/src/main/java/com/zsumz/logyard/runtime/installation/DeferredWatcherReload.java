package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.runtime.reload.WatcherReloadOutcome;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Binds a prepared watcher to its installation before the watcher thread is activated. */
final class DeferredWatcherReload implements Supplier<WatcherReloadOutcome> {
    private final AtomicReference<Supplier<WatcherReloadOutcome>> delegate = new AtomicReference<>();

    void bind(Supplier<WatcherReloadOutcome> reload) {
        if (!delegate.compareAndSet(null, Objects.requireNonNull(reload, "reload"))) {
            throw new IllegalStateException("watcher reload action is already bound");
        }
    }

    @Override
    public WatcherReloadOutcome get() {
        Supplier<WatcherReloadOutcome> reload = delegate.get();
        if (reload == null) {
            throw new IllegalStateException("watcher reload action is not bound");
        }
        return reload.get();
    }
}
