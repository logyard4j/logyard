package com.zsumz.logyard.tests;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;

public final class FallbackTestRunner {
    private FallbackTestRunner() {
    }

    public static void main(String[] classNames) throws Exception {
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        for (String className : classNames) {
            Class<?> testClass = Class.forName(className);
            Method[] methods = testClass.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName));
            for (Method method : methods) {
                if (!method.isAnnotationPresent(Test.class)) {
                    continue;
                }
                if (method.getParameterCount() != 0) {
                    skipped++;
                    System.out.printf("SKIP  %s#%s requires parameter injection%n", className, method.getName());
                    continue;
                }
                Object instance = null;
                if (!Modifier.isStatic(method.getModifiers())) {
                    var constructor = testClass.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    instance = constructor.newInstance();
                }
                method.setAccessible(true);
                try {
                    method.invoke(instance);
                    passed++;
                } catch (InvocationTargetException invocationFailure) {
                    failed++;
                    Throwable cause = invocationFailure.getCause();
                    System.err.printf("FAIL  %s#%s: %s%n", className, method.getName(), cause);
                    cause.printStackTrace(System.err);
                }
            }
        }
        if (failed != 0) {
            System.err.printf(
                    "Fallback JUnit verification failed: %d passed, %d failed, %d parameterized method(s) skipped%n",
                    passed,
                    failed,
                    skipped);
            throw new AssertionError(failed + " fallback test method(s) failed");
        }
        System.out.printf(
                "Fallback JUnit verification passed: %d test method(s), %d parameterized method(s) skipped%n",
                passed,
                skipped);
    }
}
