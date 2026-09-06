-- Content segmentation: which parts of a fetched ad are the ad, and which are the portal's
-- own furniture. `full_text` stays exactly as enrichment wrote it — this is a second reading
-- of it, not a replacement, so the record of what was fetched is never edited in place.

ALTER TABLE offer ADD COLUMN content_blocks    JSONB;
ALTER TABLE offer ADD COLUMN content_at        TIMESTAMPTZ;
ALTER TABLE offer ADD COLUMN content_model     TEXT;
ALTER TABLE offer ADD COLUMN content_undecided INTEGER;

CREATE INDEX offer_content_idx ON offer (status, content_at);

-- What a block of text means, remembered by its digest so the same paragraph is decided
-- once and free for every offer that repeats it. A portal's report dialog is byte-identical
-- in every one of its ads: eleven thousand offers, one decision.
--
-- Scoped by portal, because an identical paragraph must not be allowed to mean two things
-- across two sites. Deliberately NOT keyed by the model that answered: a score is a scale,
-- so two judges are two scales and a model change makes every score stale, but a label is a
-- fact about a paragraph and once decided it stands. `model` records who decided, for
-- auditing; it is not part of the key.
CREATE TABLE content_block_label (
    portal      TEXT        NOT NULL,
    digest      CHAR(32)    NOT NULL,
    kind        TEXT        NOT NULL,
    reason      TEXT,
    decided_by  TEXT        NOT NULL,
    model       TEXT,
    -- The first 200 characters, so a row can be read and argued with by hand. A table of
    -- hashes nobody can audit is a table nobody trusts.
    sample      TEXT        NOT NULL,
    first_seen  TIMESTAMPTZ NOT NULL DEFAULT now(),
    times_seen  INTEGER     NOT NULL DEFAULT 1,
    PRIMARY KEY (portal, digest)
);
