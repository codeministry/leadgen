/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point. Scheduling is enabled here because the pipeline runs inside this
 * process (concept § 10, phase 1) — there is no separate worker container.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LeadGenerationApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeadGenerationApplication.class, args);
    }
}
