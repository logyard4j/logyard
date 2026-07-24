package com.zsumz.logyard.spring.boot.internal.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

final class FrameworkRuntimeLifecycleTest {
    @Test
    void lifecycleEntryPointsDoNotHoldTheObjectMonitorAcrossExternalWork() throws Exception {
        for (String methodName : new String[] {"startEarly", "configure", "runtime", "close"}) {
            Method method = "configure".equals(methodName)
                    ? FrameworkRuntimeLifecycle.class.getDeclaredMethod(
                            methodName,
                            com.zsumz.logyard.runtime.bootstrap.LogyardConfigurationSource.class)
                    : FrameworkRuntimeLifecycle.class.getDeclaredMethod(methodName);
            assertFalse(Modifier.isSynchronized(method.getModifiers()));
        }
    }
}
