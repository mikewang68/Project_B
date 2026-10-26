package com.bproject.safety.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SCS 的 SSO 相关 bean 装配。
 *
 * <p>{@link ScsIamTicketValidator} 在 {@link SecurityInterceptor} 里是手动构造的
 * （因为拦截器需要在构造期就决定是否启用），而 {@code ScsSsoController} 需要它作为
 * 可注入依赖。这里提供一个共享的 bean，两处使用同一份配置，避免行为漂移。
 */
@Configuration
public class ScsSsoConfiguration {

  @Bean
  ScsIamTicketValidator scsIamTicketValidator(
      @Value("${app.security.iam-base-url:}") String iamBaseUrl,
      @Value("${app.security.iam-service-token:}") String iamServiceToken,
      @Value("${app.security.iam-audience:scs}") String iamAudience,
      @Value("${app.security.iam-timeout-ms:3000}") long iamTimeoutMs,
      ObjectMapper objectMapper) {
    return new ScsIamTicketValidator(iamBaseUrl, iamServiceToken, iamAudience, iamTimeoutMs, objectMapper);
  }

  /** 会话销毁（登出/超时）时同步解除注册表绑定，避免会话表只增不减。 */
  @Bean
  public org.springframework.boot.web.servlet.ServletListenerRegistrationBean<jakarta.servlet.http.HttpSessionListener> scsSessionCleanupListener() {
    return new org.springframework.boot.web.servlet.ServletListenerRegistrationBean<>(
        new jakarta.servlet.http.HttpSessionListener() {
          @Override
          public void sessionDestroyed(jakarta.servlet.http.HttpSessionEvent event) {
            ScsSessionRegistry.unbind(event.getSession().getId());
          }
        });
  }
}
