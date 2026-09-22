/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.application.*;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * The first write endpoint in this application, and deliberately a small one: it records
 * what a person did outside the system. It does not send anything, and there is nothing
 * here that could grow into sending — no recipient, no channel, no address.
 */
@RestController
@RequestMapping("/api/v1/applications")
class ApplicationController {

    private final ApplicationService applications;

    ApplicationController(ApplicationService applications) {
        this.applications = applications;
    }

    @GetMapping
    List<ApplicationView> board() {
        return applications.board();
    }

    /**
     * The lanes, so the board does not hardcode which states group together.
     */
    @GetMapping("/lanes")
    List<ApplicationStatus.Lane> lanes() {
        return ApplicationStatus.LANES;
    }

    /**
     * What each state may move to, so the picker and the board can grey out what this
     * endpoint would refuse.
     *
     * <p>Served rather than mirrored for the same reason the lanes are: the browser holding
     * its own copy of the rule means the two disagree the first time it changes, visibly on
     * the board and invisibly in the code. Ten of the eleven entries are the full list — the
     * map is almost entirely uninteresting, and that is the point.
     */
    @GetMapping("/transitions")
    Map<ApplicationStatus, List<ApplicationStatus>> transitions() {
        return Arrays.stream(ApplicationStatus.values())
                .collect(Collectors.toMap(
                        status -> status,
                        ApplicationStatus::allowedNext,
                        (first, second) -> first,
                        () -> new EnumMap<>(ApplicationStatus.class)));
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

    /**
     * A conflict and not a 400: the request is well formed and the state it asks for is a
     * real one. What refuses it is where the application stands right now, which is exactly
     * what 409 means and what a client can act on by moving it to PACKAGED first.
     */
    @ExceptionHandler(ApplicationService.TransitionRefused.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    String refused(ApplicationService.TransitionRefused e) {
        return e.getMessage();
    }
}
