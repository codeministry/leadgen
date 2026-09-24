-- The cover letter a package was built with, and who wrote it.
--
-- Until now the letter existed only as `cover_letter.txt` inside the package
-- folder, which the database knew nothing about beyond `package_dir`. A person
-- who reads, corrects and saves the letter needs it where the rest of the
-- application lives, and a rebuild needs to know whether the text it would
-- replace was written by a machine or by that person.
--
-- Three nullable columns and no backfill. A package built before this reads as
-- having no stored letter, and its folder still holds the file.
--
--   cover_letter_author | meaning
--   --------------------+-----------------------------------------------------
--   model               | the writing model's draft, accepted by the guard
--   template            | the .ftl, because there was no accepted draft
--   edited              | a person saved it; a rebuild never replaces it
--
-- The build writes all three after the file, in the transaction that stamps
-- `packaged_at`. Discarding a package nulls them in the same statement that
-- nulls `package_dir`, so "there is a letter" and "there is a package" stay the
-- same fact.
ALTER TABLE offer
    ADD COLUMN cover_letter_text   TEXT,
    ADD COLUMN cover_letter_author TEXT,
    ADD COLUMN cover_letter_at     TIMESTAMPTZ;

ALTER TABLE offer
    ADD CONSTRAINT offer_cover_letter_author_ck
        CHECK (cover_letter_author IN ('model', 'template', 'edited'));

COMMENT
ON COLUMN offer.cover_letter_text IS
    'The cover letter of the current package, as written to cover_letter.txt; NULL without a package or for one built before V29.';

COMMENT
ON COLUMN offer.cover_letter_author IS
    'Who wrote cover_letter_text: model (accepted draft), template (the .ftl) or edited (saved by a person).';

COMMENT
ON COLUMN offer.cover_letter_at IS
    'When cover_letter_text was last written, by a build or a save.';
