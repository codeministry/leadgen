-- The chat's conversations, their turns and the tool calls each turn made.
--
-- Written by the chat alone and read by nothing in the pipeline: a run neither needs these rows
-- nor notices them, and dropping all three loses the conversations and nothing else. The sources
-- an answer lists are not stored a second time; they are the ids its tool calls returned.
CREATE TABLE chat_conversation
(
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title           TEXT        NOT NULL,
    -- Set only by "Ask about this offer". An archived or removed offer does not take the
    -- conversation with it; the pin simply goes.
    pinned_offer_id BIGINT      REFERENCES offer (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The list is newest first, and nothing else reads the table in bulk.
CREATE INDEX chat_conversation_updated ON chat_conversation (updated_at DESC);

CREATE TABLE chat_turn
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id  BIGINT      NOT NULL REFERENCES chat_conversation (id) ON DELETE CASCADE,
    ordinal          INT         NOT NULL,
    question         TEXT        NOT NULL,
    -- As streamed, citations already resolved; grows while the turn runs.
    answer_md        TEXT        NOT NULL DEFAULT '',
    state            TEXT        NOT NULL DEFAULT 'STREAMING'
        CHECK (state IN ('STREAMING', 'DONE', 'INCOMPLETE', 'STOPPED')),
    -- Set by regenerate: the turn this one answers again. Both stay.
    replaces_turn_id BIGINT      REFERENCES chat_turn (id) ON DELETE SET NULL,
    model            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    UNIQUE (conversation_id, ordinal)
);

CREATE TABLE chat_tool_call
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    turn_id      BIGINT  NOT NULL REFERENCES chat_turn (id) ON DELETE CASCADE,
    ordinal      INT     NOT NULL,
    tool         TEXT    NOT NULL,
    label        TEXT    NOT NULL,
    arguments    JSONB   NOT NULL DEFAULT '{}'::jsonb,
    -- [{"kind": "OFFER", "id": 12}, …] — what the grounding check holds a citation against.
    returned_ids JSONB   NOT NULL DEFAULT '[]'::jsonb,
    duration_ms  INT,
    UNIQUE (turn_id, ordinal)
);
