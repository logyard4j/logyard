package com.zsumz.logyard.spring.boot.internal.actuator;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;

/** Registers a precisely typed health bean without linking one Boot line's relocated API. */
public final class LogyardHealthContributorRegistrar implements BeanDefinitionRegistryPostProcessor, PriorityOrdered {
    public static final String BEAN_NAME = "logyard";

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        if (registry.containsBeanDefinition(BEAN_NAME)) {
            return;
        }
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader classLoader = contextClassLoader == null
                ? LogyardHealthContributorRegistrar.class.getClassLoader()
                : contextClassLoader;
        ActuatorHealthApi.detect(classLoader).ifPresent(api -> register(registry, api));
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // No bean-factory mutation is required after the typed definition is registered.
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static void register(BeanDefinitionRegistry registry, ActuatorHealthApi api) {
        RootBeanDefinition definition = new RootBeanDefinition();
        definition.setBeanClass(LogyardHealthContributorFactoryBean.class);
        definition.getConstructorArgumentValues().addIndexedArgumentValue(0, api.indicatorType().getName());
        definition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, api.indicatorType());
        registry.registerBeanDefinition(BEAN_NAME, definition);
    }
}
