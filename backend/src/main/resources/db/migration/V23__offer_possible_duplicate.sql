-- The second similarity strategy does not merge. `flag_possible_duplicate` says "these two
-- might be the same project" and leaves the decision to a person, which is the honest
-- answer between the threshold that is safe to act on and the one that is not.
--
-- A column of its own rather than a second meaning for `duplicate_of_id`: that one decides
-- what the working list shows, and a maybe written into it would silently hide an offer.
ALTER TABLE offer
    ADD COLUMN possible_duplicate_of_id BIGINT REFERENCES offer (id);

-- The shortlist filters on it, and the value is NULL for almost every row, so the index is
-- partial -- it covers the rows that have one and costs nothing for the rest.
CREATE INDEX idx_offer_possible_duplicate ON offer (possible_duplicate_of_id)
    WHERE possible_duplicate_of_id IS NOT NULL;
