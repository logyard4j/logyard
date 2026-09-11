package com.logyard4j.spring.boot.internal.lifecycle;

import com.logyard4j.runtime.bootstrap.LogyardConfigurationSource;
import com.logyard4j.runtime.bootstrap.RuntimeBundle;
import com.logyard4j.runtime.bootstrap.RuntimeOwner;

/** Injectable acquisition boundary used to make lifecycle handoffs deterministic under test. */
@FunctionalInterface
interface FrameworkRuntimeAcquirer {
    RuntimeBundle acquire(RuntimeOwner owner, LogyardConfigurationSource source);
}
