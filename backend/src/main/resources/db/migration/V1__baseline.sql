-- Baseline schema. Deliberately narrow: it holds what the sample analysis proved
-- the newsletter actually delivers (docs/SAMPLE-ANALYSIS.md § 1) plus the ingest
-- bookkeeping the IMAP connector needs. Enrichment, scoring and packaging bring
-- their own migrations in later steps.

CREATE TABLE source (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         TEXT        NOT NULL UNIQUE,
    kind         TEXT        NOT NULL,
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per offer as extracted, before dedupe. `fingerprint` is the
-- normalized title plus portal: 12.3 % of a single mail are duplicates, so the
-- index that finds them has to exist from the first ingest run on.
CREATE TABLE offer (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id         BIGINT      NOT NULL REFERENCES source (id),
    external_id       TEXT,
    title             TEXT        NOT NULL,
    description       TEXT,
    url               TEXT,
    location          TEXT,
    portal            TEXT,
    agency            TEXT,
    published_on      DATE,
    fingerprint       TEXT        NOT NULL,
    duplicate_of_id   BIGINT      REFERENCES offer (id),
    status            TEXT        NOT NULL DEFAULT 'INGESTED',
    ingested_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_offer_fingerprint ON offer (fingerprint);
CREATE INDEX idx_offer_status ON offer (status);
CREATE UNIQUE INDEX uq_offer_source_external ON offer (source_id, external_id)
    WHERE external_id IS NOT NULL;

-- Progress per IMAP folder. Tracked via UIDVALIDITY/UID and never via
-- seen/unseen: the same mailbox is read on a phone, and a flag-based cursor
-- would skip everything the user opened there first.
CREATE TABLE ingest_cursor (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id     BIGINT      NOT NULL REFERENCES source (id),
    folder        TEXT        NOT NULL,
    uid_validity  BIGINT      NOT NULL,
    last_uid      BIGINT      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ingest_cursor UNIQUE (source_id, folder)
);
