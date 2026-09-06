-- What a factor could have contributed, beside what it did contribute.
--
-- The total is a share of what was attainable rather than a sum, so a factor the offer
-- said nothing about is out of both halves instead of scoring zero. Reading that back
-- needs the denominator on the row: the shortlist shows "23 of 45", and a score written
-- before this column existed has no denominator to show, which is what NULL means here.
ALTER TABLE offer_score_reason
    ADD COLUMN max_points INTEGER;

COMMENT
ON COLUMN offer_score_reason.max_points IS
    'What this factor could have contributed. NULL for rows written before scoring normalised; 0 for an absolute bonus or penalty, which is never part of what was attainable.';
