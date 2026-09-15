-- Two more sort keys, and therefore two more indexes.
--
-- `duration-asc` is the one reverse the shortlist offers: `min_months` is a floor with no
-- matching ceiling, so "which of these fills a gap" is the question no existing control can
-- ask. It reads the same column as `duration`, in the other direction — which is exactly why
-- it needs an index and a sentinel of its own. The sentinel is 9999 and not -1: a sentinel
-- puts "not stated" last under its own key's direction, and -1 under ASC would put every
-- offer that states no duration at the *front* of the list. That is a silent inversion of the
-- rows this whole design exists to protect, which is why the value now sits on the sort
-- constant in `ShortlistSort` rather than on the kind the two duration keys share.
--
-- `fresh` is the only key with nothing to fold to the end: `ingested_at` is NOT NULL from the
-- baseline, written by the upsert, so there is no coalesce and no sentinel. It is also
-- already the tiebreaker of every other tuple, so the walk compares (ingested_at,
-- ingested_at, id) and this index answers it as (ingested_at DESC, id DESC).
--
-- The expressions have to match `ShortlistSort` byte for byte, sentinel included, the same
-- rule V18 states. And nothing here may mention current_date: a sort key has to be IMMUTABLE
-- to be indexable at all.

CREATE INDEX offer_duration_months_asc_idx
    ON offer (status, (coalesce(duration_months, 9999)), ingested_at, id)
    WHERE duplicate_of_id IS NULL AND archived_at IS NULL;

CREATE INDEX offer_ingested_at_idx
    ON offer (status, ingested_at DESC, id DESC)
    WHERE duplicate_of_id IS NULL AND archived_at IS NULL;
