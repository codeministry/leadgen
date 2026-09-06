-- What each source did on each run.
--
-- Derivable from the offer table this is not: `documents` and the count a document
-- announces about itself leave no trace there, and the announced-versus-extracted
-- comparison is the one check nothing else can make. A selector that stops matching
-- loses offers, and fewer offers is indistinguishable from a quiet day on the market.
--
-- One row per source per run rather than a mutable "last run" row, because the
-- interesting question is when the number changed, not what it is now.
CREATE TABLE source_run (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id  BIGINT      NOT NULL REFERENCES source (id) ON DELETE CASCADE,
    ran_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    documents  INTEGER     NOT NULL,
    extracted  INTEGER     NOT NULL,
    written    INTEGER     NOT NULL,
    -- Null when the source states no count to check against, which is most of them.
    announced  INTEGER
);

CREATE INDEX source_run_latest_idx ON source_run (source_id, ran_at DESC);
