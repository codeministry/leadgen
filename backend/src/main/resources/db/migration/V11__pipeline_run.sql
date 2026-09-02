-- What one run did, as the run itself reported it.
--
-- Not derivable afterwards, and that is the whole reason this table exists: FilterService
-- re-judges every offer on every pass and overwrites `filter_stage`, ScoringService
-- overwrites the score columns and deletes and reinserts the reasons. Everything held here
-- is destroyed by the next run, which is why it is written at the moment it is still true.
--
-- `source_run` answers the same question one level down — per source, per document — and
-- stays. Neither table can be computed from the other.
CREATE TABLE pipeline_run (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at        TIMESTAMPTZ NOT NULL,
    finished_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A run without its scale is a number with nothing behind it. Two runs under two
    -- rulesets plotted on one line look comparable and are not.
    ruleset_version   TEXT,
    score_model       TEXT,

    -- COMPLETE, or AWAITING_BATCH when the scores were submitted and the packaging and the
    -- digest were left to ScoreBatchCollector. Without this state a batched run's row
    -- states a shortlist belonging to the previous run, and looks entirely normal doing it.
    status            TEXT    NOT NULL DEFAULT 'COMPLETE',

    documents         INTEGER NOT NULL,
    extracted         INTEGER NOT NULL,
    written           INTEGER NOT NULL,
    -- The standing total, exactly as IngestReport.merged is: a second run moves nothing,
    -- and a zero here would read as "deduplication stopped working".
    merged            INTEGER NOT NULL,

    filter_considered INTEGER NOT NULL,
    filter_passed     INTEGER NOT NULL,

    enrich_considered INTEGER NOT NULL,
    enriched          INTEGER NOT NULL,
    incomplete        INTEGER NOT NULL,
    from_cache        INTEGER NOT NULL,
    requests          INTEGER NOT NULL,

    score_considered  INTEGER NOT NULL,
    scored            INTEGER NOT NULL,
    unscored          INTEGER NOT NULL,
    shortlisted       INTEGER NOT NULL,
    review            INTEGER NOT NULL,
    submitted         INTEGER NOT NULL,

    packaged          INTEGER NOT NULL,
    digest_written    BOOLEAN NOT NULL
);

-- A row per stage rather than a column per stage: FilterStage is an enum that has grown
-- once already, and a column each would mean a migration every time it grows again.
CREATE TABLE pipeline_run_stage (
    run_id  BIGINT  NOT NULL REFERENCES pipeline_run (id) ON DELETE CASCADE,
    stage   TEXT    NOT NULL,
    removed INTEGER NOT NULL,
    PRIMARY KEY (run_id, stage)
);

CREATE INDEX pipeline_run_finished_idx ON pipeline_run (finished_at DESC);
