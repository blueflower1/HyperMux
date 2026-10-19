package com.hypermux.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** HyperMux 后端服务入口。 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class HyperMuxServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(HyperMuxServerApplication.class, args);
    }
}
