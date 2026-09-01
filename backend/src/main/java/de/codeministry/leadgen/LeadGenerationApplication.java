package de.codeministry.leadgen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point. Scheduling is enabled here because the pipeline runs inside this
 * process (concept § 10, phase 1) — there is no separate worker container.
 */
@SpringBootApplication
@EnableScheduling
public class LeadGenerationApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeadGenerationApplication.class, args);
    }
}
