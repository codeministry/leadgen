-- How long each stage of a run took, and whether it finished.
--
-- `pipeline_run` says what a run did; this says where the time went. The two are
-- separate because they answer different questions and neither is derivable from
-- the other: a run whose numbers look ordinary can still have spent four minutes
-- in enrichment because one portal was slow, and nothing in the counts says so.
--
-- Written together with the run row and after the work, never before it: the row
-- this references does not exist until the run is over, and a run that failed
-- halfway must not leave a row claiming a clean pass.
--
-- Rows rather than columns, for the same reason `pipeline_run_stage` is rows: the
-- list of stages has grown once already, and a column each would mean a migration
-- every time it grows again.
CREATE TABLE pipeline_stage (
    run_id     BIGINT      NOT NULL REFERENCES pipeline_run (id) ON DELETE CASCADE,
    -- The order the stages ran in, so a reader does not have to know it.
    position   INTEGER     NOT NULL,
    stage      TEXT        NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at   TIMESTAMPTZ NOT NULL,
    -- OK, or FAILED with the reason in `note`. A stage that threw still gets a row:
    -- "the run stopped here" is the single most useful thing this table can say.
    status     TEXT        NOT NULL,
    note       TEXT,
    PRIMARY KEY (run_id, position)
);

CREATE INDEX pipeline_stage_run_idx ON pipeline_stage (run_id);
