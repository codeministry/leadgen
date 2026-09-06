-- What happened after the package was built.
--
-- This is the one thing the system cannot observe for itself. It does not send,
-- so it cannot know that a mail went out, that someone replied, or that the
-- project was lost to a cheaper bid. Every value here is put in by hand, which
-- is also why the transitions are not policed: the operator is the authority on
-- their own mailbox, and a tool that argues with them about it is a tool they
-- stop updating.
CREATE TABLE application (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    offer_id       BIGINT      NOT NULL UNIQUE REFERENCES offer (id) ON DELETE CASCADE,
    status         TEXT        NOT NULL DEFAULT 'NEW',
    sent_on        DATE,
    follow_up_on   DATE,
    outcome        TEXT,
    note           TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The board groups eleven states into five lanes and reads them all at once.
CREATE INDEX application_status_idx ON application (status, updated_at DESC);

-- The dashboard's follow-up tile counts exactly this.
CREATE INDEX application_follow_up_idx ON application (follow_up_on) WHERE follow_up_on IS NOT NULL;

-- Every change, kept: a pipeline whose history is a single mutable row cannot
-- answer "when did I send this" after the second correction.
CREATE TABLE application_event (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id BIGINT      NOT NULL REFERENCES application (id) ON DELETE CASCADE,
    from_status    TEXT,
    to_status      TEXT        NOT NULL,
    note           TEXT,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX application_event_idx ON application_event (application_id, recorded_at DESC);
