/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.content;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.Optional;

/**
 * What a block of text was decided to be, remembered by its digest.
 *
 * <p>This is what makes asking a model affordable. A portal writes the same report dialog,
 * the same "Apply now" and the same tag-cloud shell into every one of its ads, so the first
 * offer pays for the decision and the eleven thousand behind it do not. A header nobody has
 * seen before is a digest nobody has seen before: one call, and then free again. The system
 * therefore notices new furniture by construction, rather than mislabelling it in silence —
 * which is the failure a table of hand-written selectors has and this does not.
 *
 * <p><b>Scoped by portal.</b> An identical paragraph must not be allowed to mean two things
 * across two sites, and two portals genuinely do write the same sentence for different
 * reasons.
 *
 * <p><b>Not keyed by the model that answered</b>, and that is the one place this deliberately
 * differs from scoring. A score is a scale, so two judges are two scales and a model change
 * makes every score stale. A label is a fact about a paragraph: once decided it stands, and
 * re-deciding is something a person does on purpose rather than something a configuration
 * edit triggers by accident.
 */
@Slf4j
@Component
public class BlockLabelStore {

    private static final String FIND = """
        SELECT kind, reason FROM content_block_label
        WHERE portal = ? AND digest = ?
        """;

    /**
     * The newest decision wins and the counter always moves. A rule is consulted before the
     * cache, so a rule somebody wrote after the fact overwrites what a model once said, which
     * is the right way round: the rule is the explicit decision.
     */
    private static final String REMEMBER =
        """
            INSERT INTO content_block_label (portal, digest, kind, reason, decided_by, model, sample)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (portal, digest) DO UPDATE
            SET kind = EXCLUDED.kind,
                reason = EXCLUDED.reason,
                decided_by = EXCLUDED.decided_by,
                model = EXCLUDED.model,
                times_seen = content_block_label.times_seen + 1
            """;

    private static final String SEEN_AGAIN = """
        UPDATE content_block_label SET times_seen = times_seen + 1
        WHERE portal = ? AND digest = ?
        """;

    private final JdbcClient jdbc;

    BlockLabelStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * What this block was decided to be before, if anything ever was.
     *
     * <p>A kind the enum no longer knows is treated as "never decided" rather than as an
     * error: a value removed from {@link ContentKind} would otherwise take down the stage on
     * a table full of rows nobody can migrate by hand.
     */
    public Optional<Label> find(String portal, String digest) {
        return jdbc.sql(FIND)
            .params(key(portal), digest)
            .query((rs, row) -> new Label(kindOf(rs.getString("kind")), rs.getString("reason")))
            .optional()
            .filter(label -> label.kind() != null);
    }

    @Transactional
    public void remember(
        String portal, String digest, ContentKind kind, String reason, Decider by, String model, String sample) {
        jdbc.sql(REMEMBER)
            .params(key(portal), digest, kind.name(), reason, by.name(), model, sample)
            .update();
    }

    @Transactional
    public void seenAgain(String portal, String digest) {
        jdbc.sql(SEEN_AGAIN).params(key(portal), digest).update();
    }

    private static ContentKind kindOf(String stored) {
        try {
            return ContentKind.valueOf(stored);
        } catch (IllegalArgumentException | NullPointerException e) {
            log.debug("content_block_label holds kind '{}', which this build does not know", stored);
            return null;
        }
    }

    /**
     * An offer with no portal is still an offer. The column is the key's first half, so it
     * cannot be null, and an empty string is the honest spelling of "the source did not say".
     */
    private static String key(String portal) {
        return portal == null ? "" : portal;
    }

    public record Label(ContentKind kind, String reason) {
    }
}
