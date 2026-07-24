package com.zsumz.logyard.spring.boot.internal.lifecycle;

import com.zsumz.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;
import com.zsumz.logyard.runtime.bootstrap.RuntimeOwner;

/** Injectable acquisition boundary used to make lifecycle handoffs deterministic under test. */
@FunctionalInterface
interface FrameworkRuntimeAcquirer {
    RuntimeBundle acquire(RuntimeOwner owner, LogyardConfigurationSource source);
}
