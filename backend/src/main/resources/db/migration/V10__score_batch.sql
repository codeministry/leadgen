-- Batched scoring: one row per batch handed to the provider, and a pointer on every offer
-- waiting in it.
--
-- The pointer is what keeps the staleness guard honest. Without it a submitted offer looks
-- exactly like an unjudged one to the next run, which would submit it a second time and pay
-- twice for the same answer. It also makes "what is in flight" a question the database can
-- answer, which matters because the answer outlives the process that asked.
CREATE TABLE score_batch (
    id              BIGSERIAL   PRIMARY KEY,
    -- The id the provider returned. Unique, because collecting the same batch twice would
    -- write the same scores twice and the second write would look like a fresh judgement.
    provider_id     TEXT        NOT NULL UNIQUE,
    model           TEXT        NOT NULL,
    -- The weights the requests were built against. A batch collected under different
    -- weights would mix two scales in one shortlist, so it is discarded instead.
    ruleset_version TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'SUBMITTED',
    offers          INTEGER     NOT NULL,
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    collected_at    TIMESTAMPTZ,
    note            TEXT
);

ALTER TABLE offer ADD COLUMN score_batch_id BIGINT REFERENCES score_batch (id);

-- Partial: almost every row is null almost all of the time, and the only question ever
-- asked of this column is which offers are currently waiting.
CREATE INDEX idx_offer_score_batch ON offer (score_batch_id) WHERE score_batch_id IS NOT NULL;
