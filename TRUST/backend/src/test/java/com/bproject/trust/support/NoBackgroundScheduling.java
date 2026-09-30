package com.bproject.trust.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

/** Test-only: remove scheduling infrastructure BEFORE any application bean is instantiated. */
@TestConfiguration(proxyBeanMethods = false)
public class NoBackgroundScheduling {
  @Bean
  static BeanDefinitionRegistryPostProcessor removeScheduling() {
    return new BeanDefinitionRegistryPostProcessor() {
      public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        String name = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
        if (registry.containsBeanDefinition(name)) registry.removeBeanDefinition(name);
      }
      public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) {
        if (factory.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
          throw new IllegalStateException("Test scheduling isolation failed");
      }
    };
  }
}
