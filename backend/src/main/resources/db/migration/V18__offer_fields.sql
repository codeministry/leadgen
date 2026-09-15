-- Start, duration and application deadline, read out of the advert by a model rather than by
-- a regex. Each of the three is a pair: the phrase the advert actually used, and a normalised
-- value that can be sorted and compared. An ad says "ab sofort", "Q4/2026", "zunaechst 6
-- Monate mit Option auf Verlaengerung" — throwing that away to keep a number is the same
-- mistake as keeping the number and losing the sort.
--
-- `starts_on` (DATE) and `duration` (TEXT) already exist, from V5. They are reused rather
-- than duplicated: `starts_on` keeps the resolved day and `duration` keeps the phrase.
--
-- ONE MEANING CHANGES HERE. Until now `duration` held the enrichment regex's capture group,
-- which is a bare number: "6", not "6 Monate". After the fields stage has run it holds the
-- sentence. Every row becomes due on the first pass because `fields_at` starts null
-- everywhere, so the two spellings coexist only until then.

ALTER TABLE offer ADD COLUMN start_text      TEXT;
ALTER TABLE offer ADD COLUMN duration_months INTEGER;
ALTER TABLE offer ADD COLUMN apply_by        DATE;
ALTER TABLE offer ADD COLUMN apply_by_text   TEXT;

-- The stage's own provenance, the same pair `content_at` / `content_model` carries and for
-- the same two reasons: `fields_at` is stamped only when the pass is finished with the offer,
-- so a configured model that did not answer leaves the offer due; and `fields_model IS NULL`
-- is the second half of the due query, so configuring a key later makes the standing backlog
-- due with no migration.
ALTER TABLE offer ADD COLUMN fields_at       TIMESTAMPTZ;
ALTER TABLE offer ADD COLUMN fields_model    TEXT;

CREATE INDEX offer_fields_idx ON offer (status, fields_at);

-- One index per sort key, over the working set the shortlist actually reads.
--
-- The expressions have to match `ShortlistSort`'s byte for byte, sentinel included. The
-- sentinel is not decoration: a keyset walk compares whole rows, and SQL row comparison
-- yields NULL the moment any element is NULL, so a nullable sort column would make every
-- offer that states nothing vanish from page two while still being counted. `coalesce`
-- places "not stated" last under that key's direction instead — DATE '9999-12-31' under ASC,
-- -1 under DESC. Both are IMMUTABLE, which is what makes them indexable, and it is the second
-- reason no sort key may ever mention current_date.
CREATE INDEX offer_start_idx
    ON offer (status, (coalesce(starts_on, DATE '9999-12-31')), ingested_at, id)
    WHERE duplicate_of_id IS NULL AND archived_at IS NULL;

CREATE INDEX offer_apply_by_idx
    ON offer (status, (coalesce(apply_by, DATE '9999-12-31')), ingested_at, id)
    WHERE duplicate_of_id IS NULL AND archived_at IS NULL;

CREATE INDEX offer_duration_months_idx
    ON offer (status, (coalesce(duration_months, -1)) DESC, ingested_at DESC, id DESC)
    WHERE duplicate_of_id IS NULL AND archived_at IS NULL;
