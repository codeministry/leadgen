-- The retrieval vector: the whole de-furnitured advert, as `ContentText.of` builds it.
--
-- A second column and deliberately not `offer.embedding`. That one holds the title, the location
-- and 600 characters of the advert's opening, because deduplication wants a short discriminating
-- text, and its merge band was measured against exactly that text. Retrieval wants the opposite.
-- Two questions, two columns, one model.
--
-- Re-using the dedupe column instead would be the cheap-looking option and it cannot be made
-- safe: `SimilarOffers` compares two rows only when their `embedding_model` matches, so after a
-- re-embed some rows would carry the short text's vector and some the advert's UNDER THE SAME
-- MODEL NAME, the guard would pass, and the cosine between two incomparable vectors is a number
-- rather than an error. DEDUPE also runs at position 2 and the advert does not exist until ENRICH
-- and CONTENT have run, so the population would stay mixed forever.
--
-- Measured 2026-09-17 on 252 fetched and segmented adverts, both texts over the same rows: the
-- advert text is the MORE discriminating of the two — 347 pairs above 0.8 against the teaser's
-- 488, and all ten in the merge band are one project posted twice. It is still not used for
-- deduplication, and the reason is the ordering above rather than the quality.
-- `docs/decisions/retrieval.md` carries the tables.
ALTER TABLE offer
    ADD COLUMN retrieval_embedding       vector(2000),
    ADD COLUMN retrieval_embedding_model TEXT,
    ADD COLUMN retrieval_embedded_at     TIMESTAMPTZ;

-- Same width and same index kind as `V25`, for the same two reasons: 2000 is the widest vector
-- pgvector will build an HNSW index on, and IVFFlat has to be built on a populated table to
-- choose its lists while this column starts empty on every installation.
CREATE INDEX idx_offer_retrieval_embedding
    ON offer USING hnsw (retrieval_embedding vector_cosine_ops);

-- The due query runs every night over the working list. Partial, because a row that already has
-- its vector is the overwhelming majority after the first pass.
CREATE INDEX offer_retrieval_due_idx
    ON offer (content_at) WHERE retrieval_embedded_at IS NULL;

-- Three columns and not two. `retrieval_embedded_at` is what makes the due query self-healing
-- against `content_at`: an advert re-segmented after a portal changed its markup needs a new
-- vector, and `retrieval_embedding_model IS DISTINCT FROM` cannot see that the text moved while
-- the model stayed the same.
