-- The reverse of every sort key, and the two of them that need an index.
--
-- `start-desc` and `deadline-desc` read the same columns as `start` and `deadline`, the other
-- way round, and fold "not stated" to DATE '1900-01-01' rather than to DATE '9999-12-31': a
-- sentinel puts the unstated last under its own key's direction, so the forward index, whose
-- expression holds the other day, cannot answer them — the V20 argument for `duration-asc`.
--
-- `score-asc` gets no index because `score` has none either. `fresh-asc` needs none: its
-- expression is the bare column, and offer_ingested_at_idx from V20 answers it scanned
-- backwards.
--
-- The expressions have to match `ShortlistSort` byte for byte, sentinel included, the rule
-- V18 states.

CREATE INDEX offer_start_desc_idx
    ON offer (status, (coalesce(starts_on, DATE '1900-01-01')) DESC, ingested_at DESC, id DESC)
    WHERE duplicate_of_id IS NULL AND archived_at IS NULL;

CREATE INDEX offer_apply_by_desc_idx
    ON offer (status, (coalesce(apply_by, DATE '1900-01-01')) DESC, ingested_at DESC, id DESC)
    WHERE duplicate_of_id IS NULL AND archived_at IS NULL;
