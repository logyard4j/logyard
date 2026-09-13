package com.logyard4j.logyard.spring.boot.internal.lifecycle;

import com.logyard4j.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.logyard4j.logyard.runtime.bootstrap.RuntimeBundle;
import com.logyard4j.logyard.runtime.bootstrap.RuntimeOwner;

/** Injectable acquisition boundary used to make lifecycle handoffs deterministic under test. */
@FunctionalInterface
interface FrameworkRuntimeAcquirer {
    RuntimeBundle acquire(RuntimeOwner owner, LogyardConfigurationSource source);
}
