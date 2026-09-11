package com.zsumz.logyard.tests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

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
                    try {
                        invokeAll(instance, lifecycle(methods, BeforeEach.class));
                        method.invoke(instance);
                    } finally {
                        // Cleanup must run even when the test failed, matching JUnit's contract.
                        invokeAll(instance, lifecycle(methods, AfterEach.class));
                    }
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

    private static List<Method> lifecycle(Method[] methods, Class<? extends Annotation> annotation) {
        List<Method> selected = new ArrayList<>();
        for (Method method : methods) {
            if (method.isAnnotationPresent(annotation) && method.getParameterCount() == 0) {
                method.setAccessible(true);
                selected.add(method);
            }
        }
        return selected;
    }

    private static void invokeAll(Object instance, List<Method> methods) throws Exception {
        for (Method method : methods) {
            method.invoke(instance);
        }
    }
}
