-- Where the application package for an offer was written, and when. On the offer
-- rather than in a side table: "has this been packaged" is a question every read
-- of the shortlist asks, and the answer is one folder.
ALTER TABLE offer ADD COLUMN package_dir TEXT;
ALTER TABLE offer ADD COLUMN packaged_at TIMESTAMPTZ;

-- The language the ad is written in, decided when the package is built because
-- that is the first moment it matters: it picks the cover-letter template and the
-- CV, and nothing else in the pipeline reads it.
ALTER TABLE offer ADD COLUMN language TEXT;
