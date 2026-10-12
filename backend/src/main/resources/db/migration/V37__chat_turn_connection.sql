-- Which connection answered a turn (spec 025, ISC-507): `llm`, which every stage uses, or the chat's
-- optional second connection beside it. Stored by name, never by base URL, so no address lands in a
-- row. Null for every turn stored before this column. Expand only; the previous image selects its
-- columns by name and never reads this one.
ALTER TABLE chat_turn
    ADD COLUMN connection TEXT CHECK (connection IN ('PRIMARY', 'SECOND'));

COMMENT ON COLUMN chat_turn.connection IS
    'The connection that answered the turn, PRIMARY or SECOND; null before V37.';

-- Requests the chat sent on one connection, per calendar day in the server's zone (spec 025, ISC-510).
--
-- Beside chat_call_budget and never instead of it: a call on the second connection counts against
-- both, so the chat's own ceiling still bounds every question, and the second connection's ceiling
-- bounds what a hosted, billed model may cost in a day. Same shape, same one-statement take.
CREATE TABLE chat_connection_call_budget (
    day        DATE NOT NULL,
    connection TEXT NOT NULL CHECK (connection IN ('PRIMARY', 'SECOND')),
    calls      INT  NOT NULL DEFAULT 0,
    PRIMARY KEY (day, connection)
);

COMMENT ON TABLE chat_connection_call_budget IS
    'Requests the chat sent on one connection, per calendar day in the server''s zone.';
