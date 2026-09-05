-- A run's row is written when it starts, not when it ends.
--
-- The rule it replaces was deliberate and is worth stating: the row used to be the last
-- thing a run wrote, so "a run that failed halfway must not leave a row claiming a clean
-- pass". That intent survives — a row written at the start says RUNNING and carries zeros,
-- which claims nothing. A run that dies leaves it saying RUNNING forever, which is more
-- honest than leaving no trace at all.
--
-- What forced the change is the dashboard. `source_run` has no run id, so LastRunQueryService
-- addresses its rows by time and bounds them below with the run's `started_at`. That was
-- exact only under the assumption the javadoc names: "no later run exists to contribute rows
-- above it". A run still in flight breaks it — measured on the cluster, the panel listed
-- every source twice, because rows from a pass that had begun two minutes earlier fell inside
-- the window of the last one that had finished. With a row at the start, the next run's
-- `started_at` is knowable and becomes the upper bound. It is the right bound rather than
-- `finished_at`, which the batch collector moves forward.

ALTER TABLE pipeline_run
    ALTER COLUMN finished_at DROP NOT NULL,
ALTER
COLUMN finished_at DROP
DEFAULT;

-- Nothing backfills the existing rows: they are all finished, and a null `finished_at`
-- there would mean "still running", which is the one thing they are not.

-- The reporting query now asks for finished runs only, and this is the index it reads.
-- A distinct name: V11 already has `pipeline_run_finished_idx` on `finished_at DESC`, which
-- the batch collector still uses to find the row awaiting its batch.
CREATE INDEX pipeline_run_reportable_idx ON pipeline_run (started_at DESC, id DESC) WHERE finished_at IS NOT NULL;
