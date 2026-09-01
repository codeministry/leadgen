-- What the enrichment stage adds. Every column is nullable and every one means
-- "not known" when it is null, never zero: the newsletter states a rate in 0.0 %
-- of offers, so an unfetched or unreadable ad is the normal case at first.
ALTER TABLE offer ADD COLUMN rate_eur       NUMERIC(8, 2);
ALTER TABLE offer ADD COLUMN duration       TEXT;
ALTER TABLE offer ADD COLUMN workload       TEXT;
ALTER TABLE offer ADD COLUMN remote_percent INTEGER;
ALTER TABLE offer ADD COLUMN starts_on      DATE;
ALTER TABLE offer ADD COLUMN contact        TEXT;
ALTER TABLE offer ADD COLUMN full_text      TEXT;

-- Why an offer is incomplete, and when it was last tried. `enriched_at` set with
-- a null `enrichment_note` is the only combination that means "complete".
ALTER TABLE offer ADD COLUMN enriched_at      TIMESTAMPTZ;
ALTER TABLE offer ADD COLUMN enrichment_note  TEXT;

-- The fetch cache. In the database rather than on disk: `cache_ttl` is a week,
-- the container has no volume for a scratch directory, and a cache that does not
-- survive a restart turns a rate limit into a promise nobody keeps.
CREATE TABLE fetched_page (
    url         TEXT        PRIMARY KEY,
    status      INTEGER     NOT NULL,
    body        TEXT,
    fetched_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The enrichment pass reads exactly this: what passed the filter and has not been
-- enriched since the rules last changed.
CREATE INDEX offer_enrichment_idx ON offer (status, enriched_at);
