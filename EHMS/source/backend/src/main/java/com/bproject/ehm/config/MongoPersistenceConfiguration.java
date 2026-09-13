package com.bproject.ehm.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;

/**
 * 本地与Docker演示环境继续装配MongoDB适配器；server档改由openGauss/openGemini适配器接管。
 */
@Configuration
@Profile("!server")
@ComponentScan(
        basePackages = "com.bproject.ehm",
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.bproject\\.ehm\\..*\\.adapter\\.out\\.mongo\\..*"
        )
)
public class MongoPersistenceConfiguration {
}
