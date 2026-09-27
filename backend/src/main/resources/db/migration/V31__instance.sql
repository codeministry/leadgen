-- Which database this is: one id, drawn once when this migration runs, and never shared.
--
-- Two databases that run the very same migrations still draw two different ids, which is the
-- whole point. A package folder records the id of the database that built it, and the sweep
-- that removes orphaned folders deletes only those carrying its own. Without it, "orphaned"
-- could only mean "no row in THIS database names it", and every other database pointed at the
-- same directory -- a demo stack's bind mount, a test run reading `.env` -- saw every real
-- package as an orphan. Measured: 76 folders on 2026-09-19, none left on 2026-09-26.
CREATE TABLE instance
(
    id         UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Exactly one row. A second id would make "is this ours" a question with two answers.
CREATE UNIQUE INDEX instance_single_row ON instance ((true));

INSERT INTO instance DEFAULT VALUES;
