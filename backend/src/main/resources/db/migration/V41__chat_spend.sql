-- What the chat spent, per calendar day in the server's zone and per connection (spec 026, ISC-521).
--
-- A ledger beside chat_turn and never a sum over it: a turn's row goes with its conversation
-- (ON DELETE CASCADE), and deleting a conversation must not un-spend the money its turns cost. Same
-- shape as chat_connection_call_budget, written once per turn as the turn ends, never deleted with
-- anything. The status reads its today and month sums from here alone.
CREATE TABLE chat_spend (
    day        DATE           NOT NULL,
    connection TEXT           NOT NULL CHECK (connection IN ('PRIMARY', 'SECOND')),
    cost_usd   NUMERIC(14, 8) NOT NULL DEFAULT 0,
    unpriced   INT            NOT NULL DEFAULT 0,
    unreported INT            NOT NULL DEFAULT 0,
    PRIMARY KEY (day, connection)
);

COMMENT ON TABLE chat_spend IS
    'What the chat spent per calendar day in the server''s zone and per connection; kept when a conversation is deleted.';

COMMENT ON COLUMN chat_spend.day IS
    'The day the turn started, in the server''s zone, as chat_call_budget counts its days.';

COMMENT ON COLUMN chat_spend.cost_usd IS
    'The summed stored cost of the day''s finished turns on the connection, in USD; 0 for an unbilled connection.';

COMMENT ON COLUMN chat_spend.unpriced IS
    'The day''s finished turns on the connection that stored tokens but no cost because their billed model has no price.';

COMMENT ON COLUMN chat_spend.unreported IS
    'The day''s finished turns on the connection that stored no cost because a model round that delivered part of its stream reported no usage.';

-- Why a turn's cost is missing, which the cost column alone cannot say: a billed model nobody
-- priced, or a round whose usage never arrived (the OpenAI module sends it last, so a stop, a
-- timeout or a broken line cuts it off). Such a turn keeps the tokens the other rounds reported,
-- which are then a lower bound, and stores no cost. Written by the turn with its counts.
ALTER TABLE chat_turn ADD COLUMN unreported BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN chat_turn.unreported IS
    'True when a model round of the turn delivered part of its stream and reported no usage: the tokens are a lower bound and cost_usd is NULL.';

-- From V40 on, a turn with no reported usage at all stored 0/0 and no cost, and only such a turn;
-- an unbilled one stored a cost of 0. The rows that already hold that are marked, so the backfill
-- below and every later read tell them from an unpriced turn.
UPDATE chat_turn
   SET unreported = true
 WHERE input_tokens = 0 AND output_tokens = 0 AND cost_usd IS NULL;

-- The turns already stored, so the spend reads the same before and after this migration. Only
-- ended turns from V40 on take part, as the application writes them: tokens set means the turn is
-- counted, its cost summed where one is stored, the turn counted as unreported where it is marked
-- and as unpriced where it is not. A turn still streaming is left to its own ending, which writes
-- it here then.
--
-- The day is the turn's created_at in the session's zone, as current_date is for the call
-- budgets. Run by the application's Flyway that zone is the JVM's (the JDBC driver sends it as
-- TimeZone), the same zone the status names today in; run from psql it is the server's.
INSERT INTO chat_spend (day, connection, cost_usd, unpriced, unreported)
SELECT CAST(created_at AS DATE),
       coalesce(connection, 'PRIMARY'),
       coalesce(sum(cost_usd), 0),
       count(*) FILTER (WHERE cost_usd IS NULL AND NOT unreported),
       count(*) FILTER (WHERE unreported)
  FROM chat_turn
 WHERE state <> 'STREAMING'
   AND (input_tokens IS NOT NULL OR output_tokens IS NOT NULL OR unreported)
 GROUP BY 1, 2;
