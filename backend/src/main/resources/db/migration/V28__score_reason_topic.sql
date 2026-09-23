-- The profile topic a reason row was written for, beside the label that already names it.
--
-- The shortlist filters by topic, and a filter that parsed the label would be a second
-- matcher reading prose the scorer wrote for people. The scorer decides which topic an
-- offer names and stores it here; the read side matches nothing itself and only asks this
-- column. NULL on every other factor, and on every row written before topics existed.
ALTER TABLE offer_score_reason
    ADD COLUMN topic TEXT;

COMMENT
ON COLUMN offer_score_reason.topic IS
    'The profile topic this row was scored for: set on interest_fit and disinterest_fit, NULL on every other factor.';

CREATE INDEX offer_score_reason_topic_idx
    ON offer_score_reason (topic, offer_id)
    WHERE topic IS NOT NULL;

-- The profile an offer's deterministic reasons were computed against, as a digest.
--
-- A topic edit changes the deterministic half and nothing the judge said, and the judge's
-- rows are already stored per offer. So an offer whose digest differs from the running
-- profile is re-totalled from a fresh deterministic half and its stored judged rows, with
-- no model call. NULL means never computed, which the first run after this treats as
-- different and fills in.
ALTER TABLE offer
    ADD COLUMN profile_digest TEXT;

COMMENT
ON COLUMN offer.profile_digest IS
    'SHA-256 of the skill profile the deterministic reasons were computed against; a mismatch re-totals the score without a model call.';
