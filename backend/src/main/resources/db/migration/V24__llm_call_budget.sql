-- `llm.budget.max_calls_per_day` shipped in the configuration from the beginning and was
-- read by nothing. A ceiling kept in memory would be handed out twice by a restart, and a
-- restart is what happens on the night something else goes wrong -- so the count lives here,
-- where the nightly run and a run started by hand share one day's allowance.
--
-- One row per day rather than one row per call: what is asked of this table is "how many so
-- far today", on every single call, and a table that answers it with a primary key lookup
-- stays that fast whatever the archive does. What is lost is the history of when in the day
-- the calls happened, which nothing asks for.
CREATE TABLE llm_call_budget (
    day   DATE PRIMARY KEY,
    calls INT NOT NULL DEFAULT 0
);

COMMENT ON TABLE llm_call_budget IS
    'Requests sent to a language model, per calendar day in the server''s zone.';
