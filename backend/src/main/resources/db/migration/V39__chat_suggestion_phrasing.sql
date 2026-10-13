-- The chat's suggestions as the model phrased them, kept beyond the process (spec 027, ISC-531).
--
-- A cache, not a record: the key is a hash of the model, the language, the data snapshot (which
-- names the scope) and the catalog sentences, so any change to one of them is a new row and an old
-- row is simply never asked for again. No foreign key: the snapshot names ids of pipeline_run and
-- application_event as text, and a deleted run must not cascade into a cache. The readable
-- columns are there for whoever looks at the table. Expand only; the previous image never reads it.
CREATE TABLE chat_suggestion_phrasing (
    cache_key  TEXT        PRIMARY KEY,
    snapshot   TEXT        NOT NULL,
    language   TEXT        NOT NULL,
    model      TEXT        NOT NULL,
    sentences  JSONB       NOT NULL,
    phrased    JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX chat_suggestion_phrasing_created_at ON chat_suggestion_phrasing (created_at);

COMMENT ON TABLE chat_suggestion_phrasing IS
    'Suggestion and follow-up phrasings by the chat model, keyed by a hash of model, language, snapshot and sentences.';
