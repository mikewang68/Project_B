package com.mt.wms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class MtWmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(MtWmsApplication.class, args);
    }
}
