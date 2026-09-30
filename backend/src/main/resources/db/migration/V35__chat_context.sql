-- What a conversation is about, what it is called, and what a statistics call returned.
--
-- Expand only: every column and table here is new, and pinned_offer_id stays where it is, copied
-- into chat_context and kept unread for one release. Dropping it is a later release's migration.
-- Like V32, written and read by the chat alone; the pipeline notices none of it.

-- Set by rename, NULL until then; the list and the panel show it before the derived title.
-- An emptied title is stored as NULL, so the derived one comes back.
ALTER TABLE chat_conversation ADD COLUMN custom_title TEXT;

-- What the history search matches word by word: the title and every question, lower-cased and
-- without diacritics. The chat rewrites it through its normaliser whenever the conversation
-- changes; the fill below strips the Latin accents with translate(), which is what that
-- normaliser leaves of them, for the conversations that exist before that code does. No
-- unaccent: it would need CREATE EXTENSION on every deployed database.
ALTER TABLE chat_conversation ADD COLUMN search_text TEXT NOT NULL DEFAULT '';

UPDATE chat_conversation c
   SET search_text = translate(lower(concat_ws(' ',
                                               nullif(c.title, ''),
                                               (SELECT string_agg(t.question, ' ' ORDER BY t.ordinal)
                                                  FROM chat_turn t
                                                 WHERE t.conversation_id = c.id))),
                               'àáâãäåçèéêëìíîïñòóôõöùúûüýÿ',
                               'aaaaaaceeeeiiiinooooouuuuyy');

-- What a conversation is asked about: offers, a shortlist view or an analytics window, each a
-- chip. A row carries the columns of its own kind and none of the others'. At most ten OFFER
-- rows per conversation; the repository holds that limit, not a constraint.
CREATE TABLE chat_context
(
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES chat_conversation (id) ON DELETE CASCADE,
    kind            TEXT   NOT NULL
        CHECK (kind IN ('OFFER', 'SHORTLIST_VIEW', 'ANALYTICS_WINDOW')),
    -- An offer that is removed takes its chip with it, and nothing else of the conversation.
    offer_id        BIGINT REFERENCES offer (id) ON DELETE CASCADE,
    -- The shortlist's query string as the URL carries it; empty is the unfiltered shortlist.
    query           TEXT,
    window_from     DATE,
    window_to       DATE,
    ordinal         INT    NOT NULL,
    UNIQUE (conversation_id, ordinal),
    CHECK (kind <> 'OFFER'
        OR (offer_id IS NOT NULL AND query IS NULL AND window_from IS NULL AND window_to IS NULL)),
    CHECK (kind <> 'SHORTLIST_VIEW'
        OR (query IS NOT NULL AND offer_id IS NULL AND window_from IS NULL AND window_to IS NULL)),
    CHECK (kind <> 'ANALYTICS_WINDOW'
        OR (window_from IS NOT NULL AND window_to IS NOT NULL AND window_from <= window_to
            AND offer_id IS NULL AND query IS NULL))
);

-- The same offer pinned twice is one chip, not two.
CREATE UNIQUE INDEX chat_context_offer ON chat_context (conversation_id, offer_id) WHERE offer_id IS NOT NULL;

-- Every pin "Ask about this offer" set so far, as the first chip of its conversation.
INSERT INTO chat_context (conversation_id, kind, offer_id, ordinal)
SELECT id, 'OFFER', pinned_offer_id, 1
  FROM chat_conversation
 WHERE pinned_offer_id IS NOT NULL;

-- A statistics call's result as the answer drew it: the window and its series, so a reloaded
-- turn shows the same table and sparkline. NULL for every other tool.
ALTER TABLE chat_tool_call ADD COLUMN data JSONB;
