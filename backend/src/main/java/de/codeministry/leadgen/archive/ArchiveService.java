/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.archive;

import de.codeministry.leadgen.application.ApplicationStatus;
import de.codeministry.leadgen.config.ConfigRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Takes what has aged out off the working list, and puts back what belongs on it again.
 *
 * <p>This is where the age question lives now. It used to be the hard filter's last stage,
 * which meant "too old" was a verdict about the advert; it is not. An old advert is a
 * perfectly good advert that is no longer worth answering, and the difference matters
 * because the filter's verdict is what the funnel reports and what somebody reads when
 * they ask why an offer is missing. So the number kept its name —
 * {@code rules.hard_filters.freshness.max_age_days} — and moved house.
 *
 * <p><b>Both statements are idempotent, and the second one is why.</b> While the age rule
 * lived in the filter, staleness was recomputed on every run, so widening the window
 * brought offers back. A written state that quietly stopped doing that would be a
 * behaviour change nobody asked for — hence the reconciliation: rows the pass archived
 * itself return when they are inside the window again. Rows a person archived, and rows a
 * person restored, are never touched by it.
 */
@Slf4j
@Service
public class ArchiveService {

    /**
     * Aged out.
     *
     * <p>{@code archive_source IS NULL} is not redundant beside {@code archived_at IS
     * NULL}: together they are the difference between an offer nobody has decided about
     * and one a person deliberately took back, and a restore the next run undoes is a
     * button that lies.
     *
     * <p>The application clause is the exemption. An offer somebody is working on stays on
     * the list whatever its date says, because the board is the only place that state
     * exists and archiving it away would make it invisible on the one screen that reads it.
     */
    private static final String ARCHIVE_AGED_OUT = """
        UPDATE offer SET archived_at = now(), archive_source = 'AGE'
        WHERE archived_at IS NULL
          AND archive_source IS NULL
          AND published_on IS NOT NULL
          AND published_on < :cutoff
          AND NOT EXISTS (SELECT 1 FROM application a
                           WHERE a.offer_id = offer.id AND a.status IN (:live))
        """;

    /**
     * Inside the window again, because the operator widened it. Only what the pass owns.
     */
    private static final String RESTORE_INSIDE_WINDOW = """
        UPDATE offer SET archived_at = NULL, archive_source = NULL
        WHERE archive_source = 'AGE'
          AND (:cutoff IS NULL OR published_on >= :cutoff)
        """;

    /**
     * A person's decision, in both directions.
     *
     * <p>The two are one statement because they are one decision with two values, and the
     * meaning of `RESTORED` has to be written in exactly one place: it is what stops the
     * age pass archiving the row again on the very next run. One offer and many are one
     * statement for the same reason — a second copy carrying its own {@code 'MANUAL'} would
     * be the second place.
     *
     * <p><b>Positional parameters, and it must stay that way.</b> {@code JdbcClient} is named
     * or positional per statement, never both, and a <em>named</em> parameter holding a
     * {@code Long[]} is expanded by {@code NamedParameterUtils} into a {@code ?, ?, ?}
     * placeholder list — which turns {@code = ANY (:ids)} into {@code = ANY (?, ?, ?)}, a
     * syntax error. That is why the two other array bindings in this application,
     * {@code OfferQueryService.reasonsFor} and {@code clustersFor}, pass
     * {@code ids.toArray(Long[]::new)} positionally as well. The obvious later edit — tidying
     * this back into named parameters like the rest of the file — is the one that breaks it,
     * and only against a real Postgres.
     *
     * <p><b>No {@code AND archived_at IS NULL}.</b> It would make {@code archived} mean "rows
     * that actually left the list", and it would turn a second archive of the same offer into
     * a 404 on the single-offer path — contradicting that method's own rule that clicking
     * twice is not an error.
     */
    private static final String SET_BY_HAND = """
        WITH changed AS (
            UPDATE offer
            SET archived_at    = CASE WHEN ? THEN now() ELSE NULL END,
                archive_source = CASE WHEN ? THEN 'MANUAL' ELSE 'RESTORED' END
            WHERE id = ANY (?)
            RETURNING score_value
        )
        SELECT count(*) AS archived, count(*) FILTER (WHERE score_value IS NULL) AS unscored
        FROM changed
        """;

    /**
     * Primaries, like every other number an operator reads: a duplicate is not an entry.
     */
    private static final String STANDING =
            "SELECT count(*) FROM offer WHERE archived_at IS NOT NULL AND duplicate_of_id IS NULL";

    /**
     * No date, no age. These stay on the list for as long as they exist.
     */
    private static final String UNDATED = """
        SELECT count(*) FROM offer
        WHERE published_on IS NULL AND archived_at IS NULL AND duplicate_of_id IS NULL
        """;

    /**
     * The states that mean a person has taken an offer up; decided by the enum, not here.
     */
    private static final List<String> LIVE = Arrays.stream(ApplicationStatus.values())
            .filter(ApplicationStatus::isLive)
            .map(Enum::name)
            .toList();

    private final ConfigRegistry config;
    private final JdbcClient jdbc;

    ArchiveService(ConfigRegistry config, DataSource dataSource) {
        this.config = config;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Takes one offer off the working list, or puts it back.
     *
     * <p><b>No transition is refused, and none is inferred.</b> Archiving something that is
     * already archived is not an error, it is a person clicking twice; and the age of the
     * offer is not consulted, because this is the decision that overrules the age rule
     * rather than a second application of it.
     *
     * @return false when there is no such offer, so the endpoint can answer 404 rather
     * than reporting success for a row that does not exist.
     */
    @Transactional
    public boolean setArchived(long offerId, boolean archived) {
        return setArchived(List.of(offerId), archived).archived() > 0;
    }

    /**
     * The same decision, for a set of offers, in one statement.
     *
     * <p><b>Nothing is refused and nothing is skipped.</b> An id that names no offer costs a
     * number and nothing else — {@link ArchiveResult#requested()} against
     * {@link ArchiveResult#archived()} is what makes that visible. Refusing the whole request
     * because one member has gone would lose every other decision in it for a reason nobody
     * can act on.
     *
     * <p><b>Deliberately unguarded against a running pipeline pass.</b> {@code IngestService}
     * holds a {@code tryLock} because a second pass is the same work done twice and a caller
     * that waits is a request held open for hours; neither applies to one statement over at
     * most {@link ArchiveRequest#MAX_IDS} rows. What could actually go wrong — the age pass
     * undoing this write — cannot: {@code ARCHIVE_AGED_OUT} requires {@code archive_source IS
     * NULL} and {@code RESTORE_INSIDE_WINDOW} requires {@code archive_source = 'AGE'}, so a
     * row stamped {@code MANUAL} is outside both. That is the four-state design earning its
     * keep. A 409 here would refuse the operator's own decision because a machine is busy,
     * which inverts the whole point of the archive being an axis rather than a verdict.
     *
     * <p>One log line for the set, not one per offer: five hundred of them is what makes a log
     * unreadable. The accepted cost is that a single offer's id no longer appears in the log.
     */
    @Transactional
    public ArchiveResult setArchived(Collection<Long> offerIds, boolean archived) {
        // Distinct once, and `requested` counts the result of it: `= ANY` touches a row once
        // whatever the array says, so counting the ids as they arrived would report an offer
        // that got away. `ArchiveRequest` collapses them at the door as well, which is what
        // makes MAX_IDS mean distinct offers — this is the same rule for every other caller.
        Long[] ids = offerIds.stream().distinct().toArray(Long[]::new);
        ArchiveResult result = jdbc.sql(SET_BY_HAND)
            .param(archived)
            .param(archived)
            .param(ids)
            .query((rs, row) -> new ArchiveResult(ids.length, rs.getInt("archived"), rs.getInt("unscored")))
            .single();
        if (result.archived() > 0) {
            log.info(
                "{} of {} offers were {} by hand",
                result.archived(),
                result.requested(),
                archived ? "archived" : "restored");
        }
        return result;
    }

    /**
     * One pass, against today.
     */
    @Transactional
    public ArchiveReport run() {
        return run(LocalDate.now());
    }

    /**
     * @param today what "old" is measured against. A parameter for the same reason the
     *              filter's used to be one: a test needs to ask the question its fixture was
     *              written for.
     */
    @Transactional
    public ArchiveReport run(LocalDate today) {
        LocalDate cutoff = cutoff(today);

        // Strictly before the cutoff, so an offer exactly at the limit stays. That was the
        // filter's boundary too, and moving the rule must not move the boundary by a day.
        int archived = cutoff == null
                ? 0
                : jdbc.sql(ARCHIVE_AGED_OUT)
                        .param("cutoff", cutoff)
                        .param("live", LIVE)
                        .update();
        int restored = jdbc.sql(RESTORE_INSIDE_WINDOW).param("cutoff", cutoff).update();

        var report = new ArchiveReport(
                archived,
                restored,
                jdbc.sql(STANDING).query(Integer.class).single(),
                jdbc.sql(UNDATED).query(Integer.class).single());
        log.info(
                "Archive: {} aged out, {} came back, {} archived in total{}",
                report.archived(),
                report.restored(),
                report.standing(),
                report.undated() == 0
                        ? ""
                        : ", %d offers state no date and no age rule can reach them".formatted(report.undated()));
        return report;
    }

    /**
     * The window, or null when there is none.
     *
     * <p>A configuration with no {@code freshness} block archives nothing — and brings
     * everything the pass had archived back, because the rule that put it there is gone.
     * Leaving those rows archived would keep a deleted rule in force.
     */
    private LocalDate cutoff(LocalDate today) {
        var freshness = config.snapshot().rules().hardFilters().freshness();
        return freshness == null ? null : today.minusDays(freshness.maxAgeDays());
    }
}
