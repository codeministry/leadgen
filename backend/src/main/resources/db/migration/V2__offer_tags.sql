-- The search tags the aggregator groups its offers by. Present on 100 % of the
-- measured corpus and the only pre-filter the source applies itself, so scoring
-- will want them — and re-extracting them later would mean re-reading every mail.
ALTER TABLE offer ADD COLUMN tags TEXT[] NOT NULL DEFAULT '{}';
