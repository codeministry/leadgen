-- Deduplication reads the whole table grouped by fingerprint on every run, and
-- the shortlist will read a cluster by its primary. Both are index scans or a
-- sequential scan of everything ever ingested; at 1289 offers a day the second
-- one stops being free within a month.
CREATE INDEX offer_fingerprint_idx ON offer (fingerprint);

-- Partial: only the attached rows carry a value, and they are the minority.
CREATE INDEX offer_duplicate_of_idx ON offer (duplicate_of_id) WHERE duplicate_of_id IS NOT NULL;

-- The window function partitions by fingerprint and orders by first seen, which
-- is the merge policy itself: `keep_first_seen_as_primary`.
CREATE INDEX offer_fingerprint_seen_idx ON offer (fingerprint, ingested_at, id);
