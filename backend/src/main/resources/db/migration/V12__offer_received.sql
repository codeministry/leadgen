-- When the document that carried this offer reached us.
--
-- Three dates now sit on an offer and they answer three different questions.
-- `published_on` is what the advert says about itself, `ingested_at` is when this
-- application wrote the row, and this one is when the mail arrived in the mailbox. Only the
-- last of those measures the market's own tempo: `ingested_at` also measures how often the
-- tool was run, and a truncated database moves every row to the moment it was refilled.
--
-- The value was already being read (`RawDocument.receivedAt`, from the IMAP received date
-- or the .eml Sent header) and thrown away at the mapper. This keeps it.
--
-- Nullable, because a source need not be a mail at all: a file dropped in the manual inbox
-- has no arrival date, and a null there is the honest answer rather than the file's mtime.
ALTER TABLE offer ADD COLUMN received_at TIMESTAMPTZ;

CREATE INDEX offer_received_idx ON offer (received_at);
