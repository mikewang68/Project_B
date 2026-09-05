package com.bdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class BdemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(BdemoApplication.class, args);
    }
}
