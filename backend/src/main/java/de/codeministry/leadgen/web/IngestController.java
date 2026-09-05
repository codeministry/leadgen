/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.analytics.LastRunQueryService;
import de.codeministry.leadgen.analytics.LastRunView;
import de.codeministry.leadgen.ingest.IngestReport;
import de.codeministry.leadgen.ingest.IngestService;
import de.codeministry.leadgen.score.Judges;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Runs one ingest pass on demand. The scheduled run comes with the IMAP connector; until
 * then this is how a pass is triggered, and it stays useful afterwards for replaying a
 * file drop without waiting for a schedule.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class IngestController {

    private final IngestService ingest;
    private final LastRunQueryService lastRun;

    /**
     * @param model which judge scores this pass, or absent for the configured default. The
     *     choice belongs to the run and not to the server: it is made in the select beside
     *     the button, travels with the request, and nothing about it is remembered
     *     afterwards. What it costs is in {@code ScoringService.run}.
     */
    @PostMapping("/ingest")
    IngestReport run(@RequestParam(required = false) String model) {
        return ingest.run(model);
    }

    /**
     * What the last run did, read back from the tables that outlive it.
     *
     * <p>Beside `POST /ingest` rather than under `/analytics`, because it answers about the
     * same thing that endpoint produces — and because the dashboard asking "has anything
     * run" should not have to fetch the whole analytics payload to find out.
     *
     * <p>204 and not an empty object: "nothing has ever run" is a state the caller has to
     * distinguish from "a run with all counts at zero", and a body that has to be inspected
     * to tell them apart is a body that eventually gets inspected wrongly.
     */
    @GetMapping("/ingest/last")
    ResponseEntity<LastRunView> last() {
        // Not `ResponseEntity.of`, which answers 404 for an empty Optional. A 404 here
        // would say the endpoint does not exist, and the browser would treat it as the
        // error it treats every other 404 as — while the truthful answer is that the
        // endpoint is fine and there is simply no run to report.
        return lastRun.lastRun().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent()
                .build());
    }

    /**
     * 400 rather than 404: the request reached the right endpoint and named a model nobody
     * configured. The sentence lists the ones that exist, because the usual cause is a
     * choice the browser kept in localStorage after it was removed from `.env`.
     */
    /**
     * 409 rather than 429 or a queued 202: the request is refused because of the state of
     * the resource, not because the caller asked too often, and nothing is queued — a
     * second pass is the same work done twice, not work waiting its turn. The browser shows
     * the sentence; the CronJob's curl exits non-zero and the job is marked failed, which is
     * the honest record of a night that skipped.
     */
    @ExceptionHandler(IngestService.AlreadyRunning.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    String alreadyRunning(IngestService.AlreadyRunning e) {
        return e.getMessage();
    }

    @ExceptionHandler(Judges.UnknownModel.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String unknownModel(Judges.UnknownModel e) {
        return e.getMessage();
    }
}
