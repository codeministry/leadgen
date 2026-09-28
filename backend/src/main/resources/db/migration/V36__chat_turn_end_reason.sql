-- Why a turn ended incomplete (spec 022, ISC-473): the reason its live `error` event named, kept so a
-- reload can name it too. Null for every other ending and for every turn stored before this column.
-- Expand only; the previous image selects its columns by name and never reads this one.
ALTER TABLE chat_turn
    ADD COLUMN end_reason TEXT CHECK (end_reason IN ('MODEL', 'BUDGET', 'ROUNDS'));
