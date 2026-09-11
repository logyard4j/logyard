package com.logyard4j.spring.boot.internal.actuator;

import com.logyard4j.api.Logyard;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/** Supplies fresh Logyard health for both Boot health-indicator interfaces. */
final class LogyardHealthContributorInvocation implements InvocationHandler {
    private final ActuatorHealthApi api;

    LogyardHealthContributorInvocation(ActuatorHealthApi api) {
        this.api = api;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) {
        if (method.getDeclaringClass() == Object.class) {
            return objectMethod(proxy, method, arguments);
        }
        if ("health".equals(method.getName()) || "getHealth".equals(method.getName())) {
            return api.health(Logyard.runtime().health());
        }
        throw new UnsupportedOperationException("Unsupported Spring Boot health method: " + method);
    }

    private static Object objectMethod(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "toString" -> "LogyardHealthContributor";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> throw new UnsupportedOperationException("Unsupported Object method: " + method);
        };
    }
}
