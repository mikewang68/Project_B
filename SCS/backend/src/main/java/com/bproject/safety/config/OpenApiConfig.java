package com.bproject.safety.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI / Swagger UI 文档元信息。 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI safetyOpenApi() {
        return new OpenAPI().info(new Info()
                .title("装卸作业安全卡控系统 Backend Demo API")
                .version("v1")
                .description("Backend Demo 阶段一：公共能力 + 基础设施连接。业务 API 统一前缀 /api/v1。")
                .license(new License().name("Demo").url("https://example.invalid")));
    }
}
