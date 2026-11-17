package com.changeimpact;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ChangeImpact AI — Main application entry point.
 *
 * <p>This is a modular Spring Boot monolith. Each top-level package under
 * {@code com.changeimpact} represents a cohesive domain module that can be
 * extracted into an independent service if scaling requirements justify it.
 *
 * <p>Key responsibilities at startup:
 * <ul>
 *   <li>Bootstrap Spring context and all auto-configurations</li>
 *   <li>Execute Flyway database migrations</li>
 *   <li>Register RabbitMQ queues and exchanges</li>
 *   <li>Expose Spring Boot Actuator health endpoints</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
public class ChangeImpactApplication {

    public static void main(String[] args) {
        System.setProperty("java.net.preferIPv6Addresses", "true");
        SpringApplication.run(ChangeImpactApplication.class, args);
    }
}

