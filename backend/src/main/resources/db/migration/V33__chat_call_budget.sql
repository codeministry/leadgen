-- Requests the chat sent to a language model, per calendar day in the server's zone.
--
-- Its own table beside llm_call_budget and never a row in it: that table is keyed by the day
-- alone, and a shared counter would let a long afternoon of questions starve the nightly run,
-- or a spent night silence the chat. Same shape, same one-statement take, separate count.
CREATE TABLE chat_call_budget (
    day   DATE PRIMARY KEY,
    calls INT NOT NULL DEFAULT 0
);

COMMENT ON TABLE chat_call_budget IS
    'Requests the chat sent to a language model, per calendar day in the server''s zone.';
