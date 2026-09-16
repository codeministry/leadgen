-- `V22` sized the column for `nomic-embed-text`, which is what a local Ollama offers and the
-- only embedding model that was to hand. Measured against 2222 real adverts it is the wrong
-- one for this market: at the shipped flag threshold of 0.85 it paired 12147 of them, roughly
-- eleven flags per offer, and at the merge threshold of 0.92 it put "Smalltalk Visual Works
-- Entwickler" and "Fullstack Entwickler" from one agency's template at 0.9346. It is trained
-- on English and the adverts are German.
--
-- `qwen3-embedding:8b` pairs 3397 at the same 0.85 and keeps the genuine duplicates in a clean
-- tail above 0.96 -- but it returns 4096 dimensions, and pgvector 0.8.6 refuses an HNSW index
-- above 2000 for `vector` and above 4000 for `halfvec`. Both measured, not read.
--
-- 2000 is therefore the widest indexable vector, and it is nearly free: the model is trained
-- with Matryoshka representation learning, so the leading dimensions carry the separation.
-- Truncated to 2000 it pairs 3470 at 0.85 against 3397 at full width, two percent apart. The
-- truncation happens at the seam in OfferEmbedder; nothing downstream knows about it, because
-- cosine distance does not care about a vector's length.

DROP INDEX IF EXISTS idx_offer_embedding;

-- Dropped and re-added rather than widened. A vector of one width is not a vector of another
-- with zeroes on the end, and `embedding_model` has to go with it: a row that kept its name
-- while losing its vector would be compared to rows that still had one. Empty columns and a
-- null model are what the due query in `OfferEmbedder` is already looking for, so the next
-- run refills them with no further migration.
ALTER TABLE offer
    DROP COLUMN embedding,
    DROP COLUMN embedding_model;

ALTER TABLE offer
    ADD COLUMN embedding       vector(2000),
    ADD COLUMN embedding_model TEXT;

CREATE INDEX idx_offer_embedding ON offer USING hnsw (embedding vector_cosine_ops);
