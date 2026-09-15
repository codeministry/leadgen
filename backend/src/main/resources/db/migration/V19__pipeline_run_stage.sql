-- Where a run that is still going has got to.
--
-- `pipeline_run` has opened its row at the start since V15, saying RUNNING and carrying
-- zeros, so a pass in flight is already a row somebody can find. What it could not say is
-- what the pass is doing — and a run takes eleven minutes on the deployed corpus, which is
-- long enough for "is anything happening at all" to be the question.
--
-- Four columns and not a second table. `pipeline_stage` is the record of where the time went
-- and is written after the work, deliberately: the run that references it must be over
-- before it can claim anything. This is the opposite kind of fact — the one stage that is
-- happening right now — so it overwrites itself and belongs on the run's own row.
ALTER TABLE pipeline_run ADD COLUMN stage            TEXT;
ALTER TABLE pipeline_run ADD COLUMN stage_position   INTEGER;

-- How many stages this run will have. Not a constant: one stage is timed per enabled source,
-- so the total is a property of the configuration the run started under and has to be
-- written down while that is known.
ALTER TABLE pipeline_run ADD COLUMN stage_total      INTEGER;
ALTER TABLE pipeline_run ADD COLUMN stage_started_at TIMESTAMPTZ;

-- One row at most matches, and it is looked up on every poll while a run is going.
CREATE INDEX pipeline_run_running_idx ON pipeline_run (started_at DESC) WHERE finished_at IS NULL;
