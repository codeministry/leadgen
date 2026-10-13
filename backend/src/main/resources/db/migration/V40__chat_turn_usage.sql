-- What a turn's model calls used and cost (spec 026, ISC-518, ISC-519). The tokens are the
-- provider's own count, summed over every call the turn made, tool rounds included; the cost is
-- those tokens at the price in force when the turn ran, in USD, exact. All three null for every
-- turn stored before this migration, which reads as no data, never as zero: tokens set with a null
-- cost is a billed model without a price, a cost of 0 is a connection nobody bills. Expand only;
-- the previous image selects its columns by name and never reads these.
ALTER TABLE chat_turn
    ADD COLUMN input_tokens  INT,
    ADD COLUMN output_tokens INT,
    ADD COLUMN cost_usd      NUMERIC(14, 8);

COMMENT ON COLUMN chat_turn.input_tokens IS
    'Input tokens the provider reported, summed over every model call of the turn; null before V40.';

COMMENT ON COLUMN chat_turn.output_tokens IS
    'Output tokens the provider reported, summed over every model call of the turn; null before V40.';

COMMENT ON COLUMN chat_turn.cost_usd IS
    'The turn''s cost in USD at the price in force when it ran; 0 unbilled, null unpriced or before V40.';
