package com.bproject.ehm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootApplication
@ComponentScan(
        basePackages = "com.bproject.ehm",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.bproject\\.ehm\\..*\\.adapter\\.out\\.mongo\\..*"
        )
)
public class EhmDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(EhmDemoApplication.class, args);
    }
}
