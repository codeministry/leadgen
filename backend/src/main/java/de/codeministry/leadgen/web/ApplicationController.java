/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.application.ApplicationEvent;
import de.codeministry.leadgen.application.ApplicationService;
import de.codeministry.leadgen.application.ApplicationStatus;
import de.codeministry.leadgen.application.ApplicationUpdate;
import de.codeministry.leadgen.application.ApplicationView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The first write endpoint in this application, and deliberately a small one: it records
 * what a person did outside the system. It does not send anything, and there is nothing
 * here that could grow into sending — no recipient, no channel, no address.
 */
@RestController
@RequestMapping("/api/applications")
class ApplicationController {

    private final ApplicationService applications;

    ApplicationController(ApplicationService applications) {
        this.applications = applications;
    }

    @GetMapping
    List<ApplicationView> board() {
        return applications.board();
    }

    /** The lanes, so the board does not hardcode which states group together. */
    @GetMapping("/lanes")
    List<ApplicationStatus.Lane> lanes() {
        return ApplicationStatus.LANES;
    }

    @GetMapping("/{id}/history")
    List<ApplicationEvent> history(@PathVariable long id) {
        return applications.history(id);
    }

    @PatchMapping("/{id}")
    ApplicationView update(@PathVariable long id, @Valid @RequestBody ApplicationUpdate update) {
        return applications.update(id, update);
    }

    @ExceptionHandler(ApplicationService.ApplicationNotFound.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(ApplicationService.ApplicationNotFound e) {
        return e.getMessage();
    }
}
