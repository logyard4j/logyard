package com.logyard4j.spring.boot.internal.actuator;

import java.lang.reflect.Proxy;
import java.util.Objects;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.FactoryBean;

/** AOT-compatible factory for the health interface supplied by the active Spring Boot line. */
public final class LogyardHealthContributorFactoryBean implements FactoryBean<Object>, BeanClassLoaderAware {
    private final String indicatorTypeName;

    private ClassLoader classLoader = LogyardHealthContributorFactoryBean.class.getClassLoader();
    private ActuatorHealthApi api;

    public LogyardHealthContributorFactoryBean(String indicatorTypeName) {
        this.indicatorTypeName = Objects.requireNonNull(indicatorTypeName, "indicatorTypeName");
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        api = null;
    }

    @Override
    public Object getObject() {
        ActuatorHealthApi activeApi = api();
        return Proxy.newProxyInstance(
                classLoader,
                new Class<?>[] {activeApi.indicatorType()},
                new LogyardHealthContributorInvocation(activeApi));
    }

    @Override
    public Class<?> getObjectType() {
        return api().indicatorType();
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    private ActuatorHealthApi api() {
        ActuatorHealthApi current = api;
        if (current != null) {
            return current;
        }
        ActuatorHealthApi detected = ActuatorHealthApi.detect(classLoader)
                .orElseThrow(() -> new IllegalStateException("Spring Boot health API disappeared after registration"));
        if (!detected.indicatorType().getName().equals(indicatorTypeName)) {
            throw new IllegalStateException(
                    "Spring Boot health API changed during registration: expected "
                            + indicatorTypeName + " but found " + detected.indicatorType().getName());
        }
        api = detected;
        return detected;
    }
}
