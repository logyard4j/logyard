package com.logyard4j.slf4j;

import com.logyard4j.api.LogyardLogger;
import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.slf4j.internal.context.ContextSnapshotPolicy;
import com.logyard4j.slf4j.internal.context.LogyardMdcAdapter;
import com.logyard4j.slf4j.internal.event.Slf4jEventMapper;
import com.logyard4j.slf4j.internal.factory.LogyardLoggerFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Slf4jAdmissionReentryTest {
    @Test
    void guardsRuntimeAndLevelCallbacksEvenAfterWarmingTheLogger() {
        AtomicInteger calls = new AtomicInteger();
        Logger[] facade = new Logger[1];
        var delegate = (LogyardLogger) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{LogyardLogger.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("name")) return "reentry";
                    if (method.getName().equals("isEnabled")) {
                        if (calls.incrementAndGet() > 2) throw new AssertionError("recursive level callback");
                        assertFalse(facade[0].isInfoEnabled());
                        return true;
                    }
                    throw new AssertionError("unexpected callback: " + method.getName());
                });
        var runtime = (LogyardRuntime) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{LogyardRuntime.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("logger")) return delegate;
                    throw new AssertionError("unexpected callback: " + method.getName());
                });
        facade[0] = new LogyardLoggerFactory(runtime,
                new Slf4jEventMapper(new LogyardMdcAdapter(), new ContextSnapshotPolicy(List.of())))
                .getLogger("reentry");
        assertTrue(facade[0].isInfoEnabled());
        assertTrue(facade[0].isInfoEnabled());
        assertEquals(2, calls.get());
    }
}
