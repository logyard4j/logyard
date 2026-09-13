package com.logyard4j.logyard.spring.boot.internal.actuator;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/** Registers a precisely typed health bean during configuration parsing and AOT processing. */
public final class LogyardHealthContributorRegistrar implements ImportBeanDefinitionRegistrar {
    public static final String BEAN_NAME = "logyard";

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        if (registry.containsBeanDefinition(BEAN_NAME)) {
            return;
        }
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader classLoader = contextClassLoader == null
                ? LogyardHealthContributorRegistrar.class.getClassLoader()
                : contextClassLoader;
        ActuatorHealthApi.detect(classLoader).ifPresent(api -> register(registry, api));
    }

    private static void register(BeanDefinitionRegistry registry, ActuatorHealthApi api) {
        RootBeanDefinition definition = new RootBeanDefinition();
        definition.setBeanClass(LogyardHealthContributorFactoryBean.class);
        definition.getConstructorArgumentValues().addIndexedArgumentValue(0, api.indicatorType().getName());
        definition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, api.indicatorType());
        registry.registerBeanDefinition(BEAN_NAME, definition);
    }
}
