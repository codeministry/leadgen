-- The citations a turn's answer resolved, in the order they were numbered.
--
-- [{"n": 1, "kind": "OFFER", "id": 42}, …]. The answer's Markdown carries `[n](cite:offer/42)`;
-- this is the list that numbers them, so a reloaded conversation shows the same sources under
-- the same numbers without re-reading the text. The tool calls keep what was returned; this
-- keeps what the answer relied on, which is the smaller set the screen lists.
ALTER TABLE chat_turn ADD COLUMN citations JSONB NOT NULL DEFAULT '[]';
