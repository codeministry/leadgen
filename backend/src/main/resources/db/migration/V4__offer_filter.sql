-- The hard filter's verdict, kept on the offer rather than in a side table: every
-- read of an offer wants to know whether it survived, and a rejection without its
-- reason is a number nobody trusts a week later.
--
-- `status` already existed with the default 'INGESTED'. It now also takes
-- 'PASSED' and 'FILTERED_OUT'.
ALTER TABLE offer ADD COLUMN filter_stage TEXT;
ALTER TABLE offer ADD COLUMN filter_reason TEXT;

-- The shortlist reads exactly this: what survived, newest first.
CREATE INDEX offer_status_idx ON offer (status, ingested_at DESC);
