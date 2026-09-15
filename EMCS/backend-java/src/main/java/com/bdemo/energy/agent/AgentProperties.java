package com.bdemo.energy.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

// REQ-AGENT-TBD: 云模型配置只在服务端读取，不返回前端。
@ConfigurationProperties("b-demo.agent")
public record AgentProperties(boolean enabled, String baseUrl, String apiKey, String model,
                              int timeoutSeconds, int maxTokens, double temperature) { }
