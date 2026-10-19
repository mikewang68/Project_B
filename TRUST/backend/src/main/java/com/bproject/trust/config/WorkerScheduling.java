package com.bproject.trust.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Network work never runs on the lease heartbeat scheduler. */
@Configuration
public class WorkerScheduling {
  @Bean
  public ThreadPoolTaskScheduler taskScheduler() {
    return scheduler("trust-trigger-", 2);
  }

  @Bean
  public ThreadPoolTaskScheduler leaseScheduler() {
    return scheduler("trust-lease-", 1);
  }

  private ThreadPoolTaskScheduler scheduler(String prefix, int size) {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(size);
    scheduler.setThreadNamePrefix(prefix);
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }
}
