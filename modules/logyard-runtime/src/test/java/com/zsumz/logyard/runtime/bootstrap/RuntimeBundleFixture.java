package com.zsumz.logyard.runtime.bootstrap;

import com.zsumz.logyard.api.LogyardRuntime;

/** Test-only access to package-private runtime-bundle construction. */
public final class RuntimeBundleFixture {
    private RuntimeBundleFixture() {
    }

    public static RuntimeBundle create(LogyardRuntime runtime) {
        return new RuntimeBundle(null, runtime);
    }
}
