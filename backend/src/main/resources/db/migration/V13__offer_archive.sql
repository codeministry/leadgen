-- The archive: what is no longer on the working list.
--
-- Deliberately not a value in `status`. The hard filter reads every row and
-- writes a verdict onto every row on every run, because the rules are
-- hot-reloadable and a partial re-judge would split the archive across two rule
-- sets. An ARCHIVED status would therefore be overwritten by PASSED on the next
-- run -- silently, and only for the offers that still pass.
--
-- So this is its own axis, orthogonal to the verdict: an offer can be archived
-- and have passed the filter, and the two facts stay separately readable.
ALTER TABLE offer
    ADD COLUMN archived_at    TIMESTAMPTZ,
    ADD COLUMN archive_source TEXT;

-- Two columns rather than one flag, because there are four states and not two:
--
--   archived_at | archive_source | meaning
--   ------------+----------------+--------------------------------------------
--   null        | null           | on the working list, never touched
--   set         | AGE            | aged out; comes back if the window widens
--   set         | MANUAL         | a person took it out; the age pass never touches it
--   null        | RESTORED       | a person took it back; the age pass never archives it again
--
-- The fourth row is the reason for the second column. A restore that the next
-- run undoes is a button that lies.
ALTER TABLE offer
    ADD CONSTRAINT offer_archive_source_ck
        CHECK (archive_source IS NULL OR archive_source IN ('AGE', 'MANUAL', 'RESTORED'));

-- The predicate that means "this is on my list today" is
-- `status = 'PASSED' AND duplicate_of_id IS NULL AND archived_at IS NULL`, and
-- the archive is by far the most selective third of it once a week has passed.
CREATE INDEX offer_working_idx ON offer (status, ingested_at DESC) WHERE archived_at IS NULL;

-- The age question used to be the filter's last stage. It moves here, so the
-- rows it had already decided move with it: an offer rejected as STALE is an
-- offer that aged out, which is exactly what the age pass now records.
UPDATE offer SET archived_at = now(), archive_source = 'AGE' WHERE filter_stage = 'STALE';

-- And the stage itself stops existing. The next run re-judges every row anyway,
-- so this only matters for the minutes in between -- but a funnel counting a
-- stage the enum no longer has is a number with nothing behind it.
UPDATE offer
SET status = 'INGESTED', filter_stage = NULL, filter_reason = NULL
WHERE filter_stage = 'STALE';
