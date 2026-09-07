/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.archive.ArchiveRequest;
import de.codeministry.leadgen.archive.ArchiveResult;
import de.codeministry.leadgen.archive.ArchiveService;
import de.codeministry.leadgen.offer.*;
import de.codeministry.leadgen.score.Judges;
import de.codeministry.leadgen.score.ScoringService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * The shortlist and one offer of it.
 *
 * <p>The filters travel with the request and the query string still drives them, so a
 * filtered shortlist survives a reload and is shareable as a link while the deciding
 * happens once, in SQL. The one write here is the archive: everything else about an offer
 * is the pipeline's and is rewritten on the next run.
 */
@RestController
@RequestMapping("/api/offers")
class OfferController {

    private final OfferQueryService offers;
    private final ScoringService scoring;
    private final ArchiveService archive;

    OfferController(OfferQueryService offers, ScoringService scoring, ArchiveService archive) {
        this.offers = offers;
        this.scoring = scoring;
        this.archive = archive;
    }

    /**
     * One page of the shortlist, filtered.
     *
     * <p>This endpoint used to take no parameters at all, and the comment above said why:
     * the browser held the whole list and filtered it, so a filtered view survived a reload
     * as a link and the rules existed in one place. At 2,219 survivors the whole list was
     * 3 MB and growing with every newsletter, and a page of a browser-filtered list is not a
     * page of anything. So the filters moved into SQL — where they still exist once, not
     * twice — and the query string still drives them, so the link still works.
     */
    @GetMapping
    ShortlistPage shortlist(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String band,
            @RequestParam(required = false) String portal,
            @RequestParam(required = false, defaultValue = "false") boolean archived,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "0") int limit) {
        return offers.shortlist(new ShortlistQuery(q, band, portal, archived, cursor, limit));
    }

    /**
     * Mapped before `/{id}` in specificity, not in order: Spring prefers the literal path,
     * so "funnel" never reaches the long parameter.
     */
    @GetMapping("/funnel")
    FunnelView funnel() {
        return offers.funnel();
    }

    @GetMapping("/{id}")
    ShortlistEntry one(@PathVariable long id) {
        return offers.find(id).orElseThrow(() -> new NotFound(id));
    }

    /**
     * Judge this one offer again, now.
     *
     * <p>A run judges only what is stale, which is what stops it paying for the whole
     * standing backlog every night. This is the deliberate exception to that: the operator
     * is looking at the offer and has a reason the tool cannot know — a bad answer, a page
     * that only became reachable later, a rule tightened since. It costs exactly one call,
     * and it is a POST because it spends money and rewrites the score.
     *
     * <p>The answer is the whole entry rather than the score alone, so the browser replaces
     * the row with what the server actually stored instead of patching its own copy.
     */
    @PostMapping("/{id}/score")
    ShortlistEntry rescore(@PathVariable long id, @RequestParam(required = false) String model) {
        if (scoring.rescore(id, model).isEmpty()) {
            throw new NotOnTheShortlist(id);
        }
        return offers.find(id).orElseThrow(() -> new NotFound(id));
    }

    /**
     * Take this offer off the working list, or put it back.
     *
     * <p>A PATCH and not two endpoints: archiving and restoring are one decision with two
     * values, and the restore half writes a marker the age pass reads — splitting them
     * would put that meaning in two places. The answer is the whole entry, like the
     * rescore's, so the browser replaces its row with what the server stored rather than
     * patching its own copy and disagreeing with the database until the next reload.
     */
    @PatchMapping("/{id}")
    ShortlistEntry patch(@PathVariable long id, @Valid @RequestBody OfferPatch patch) {
        if (!archive.setArchived(id, patch.archived())) {
            throw new NotFound(id);
        }
        return offers.find(id).orElseThrow(() -> new NotFound(id));
    }

    /**
     * Take a set of offers off the working list in one request.
     *
     * <p>A POST on the collection and not a PATCH: the shortlist is a query result, not a
     * resource, so this is a named action of the kind {@code /{id}/score} already is. The
     * literal {@code /archive} wins over any future {@code /{id}} POST by specificity, the
     * same way {@code /funnel} does on the read side.
     *
     * <p>It answers a report rather than the rows, and the reason is written on
     * {@link ArchiveResult}: the list drops what it archives instead of replacing it, so
     * entries here would be fetched only to be discarded.
     *
     * <p><b>No 404, and that is the decision.</b> The single PATCH answers 404 because a
     * person asked a question about one offer and "there is no such offer" is the honest
     * reply. This is a set operation: refusing fifty decisions because one member has gone
     * loses forty-nine of them for a reason nobody can act on. The difference between
     * {@code requested} and {@code archived} is what makes the discrepancy visible instead —
     * the shape {@code expect_count_from_subject} uses, where a mismatch is reported loudly
     * and nothing that did come through is thrown away.
     */
    @PostMapping("/archive")
    ArchiveResult archiveAll(@Valid @RequestBody ArchiveRequest request) {
        return archive.setArchived(request.ids(), true);
    }

    /**
     * 409 rather than 404: the offer exists and the page showing it is right. What cannot
     * be done is score it, and the sentence says which of the two reasons applies.
     */
    @ExceptionHandler({NotOnTheShortlist.class, ScoringService.NoJudge.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    String cannotScore(RuntimeException e) {
        return e.getMessage();
    }

    /**
     * Same sentence and the same reason as the one on the ingest endpoint.
     */
    @ExceptionHandler(Judges.UnknownModel.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String unknownModel(Judges.UnknownModel e) {
        return e.getMessage();
    }

    @ExceptionHandler(NotFound.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(NotFound e) {
        return e.getMessage();
    }

    static class NotFound extends RuntimeException {
        NotFound(long id) {
            super("no offer with id " + id);
        }
    }

    static class NotOnTheShortlist extends RuntimeException {
        NotOnTheShortlist(long id) {
            super("offer " + id + " was rejected by the filter or is a duplicate, so it was never scored");
        }
    }
}
