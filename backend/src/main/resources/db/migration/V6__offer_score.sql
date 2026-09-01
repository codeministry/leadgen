-- The score and the reasons behind it. The reasons are the part the operator
-- actually reads: a number without a reason gets ignored within a week, and this
-- is a tool whose whole output is a ranked list someone has to trust.
--
-- `score_value` is NULL when no language model was configured. That is not zero
-- and not a failure: the deterministic reasons are still there, and a total
-- computed from half the weights would not be comparable to one computed from all
-- of them.
ALTER TABLE offer ADD COLUMN score_value      INTEGER;
ALTER TABLE offer ADD COLUMN score_band       TEXT;
ALTER TABLE offer ADD COLUMN score_model      TEXT;
ALTER TABLE offer ADD COLUMN ruleset_version  TEXT;
ALTER TABLE offer ADD COLUMN scored_at        TIMESTAMPTZ;

-- One row per contributing factor, so a reason can be read back beside its points
-- rather than parsed out of a rendered sentence.
CREATE TABLE offer_score_reason (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    offer_id BIGINT NOT NULL REFERENCES offer (id) ON DELETE CASCADE,
    factor   TEXT   NOT NULL,
    label    TEXT   NOT NULL,
    points   INTEGER NOT NULL,
    position INTEGER NOT NULL
);

CREATE INDEX offer_score_reason_offer_idx ON offer_score_reason (offer_id, position);

-- The shortlist and the digest read this: what survived, best first.
CREATE INDEX offer_score_idx ON offer (status, score_value DESC NULLS LAST);
