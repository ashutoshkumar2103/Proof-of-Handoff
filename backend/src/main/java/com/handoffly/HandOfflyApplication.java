package com.handoffly;

import com.handoffly.common.config.HandOfflyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * HandOffly — universal Proof-of-Handoff and Return Tracking.
 * Modular monolith entry point.
 */
@SpringBootApplication
@EnableConfigurationProperties(HandOfflyProperties.class)
@EnableJpaAuditing
public class HandOfflyApplication {

    public static void main(String[] args) {
        SpringApplication.run(HandOfflyApplication.class, args);
    }
}
