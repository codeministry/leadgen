-- Deduplication has one strategy that works on identity: the same normalized title, twice.
-- The two strategies the shipped `matching-rules.yaml` has always listed beside it compare
-- adverts that are *not* identical -- the same project through two portals, written up by
-- two people -- and that is a vector comparison.
--
-- pgvector rather than an array of floats and a loop in Java: the comparison is a nearest
-- neighbour search over a window of offers, it is the one thing a database index is for,
-- and `<=>` is a single operator against one already in the image.

CREATE EXTENSION IF NOT EXISTS vector;

-- 768 is not a preference, it is the shape of the vectors a model returns, and the column
-- cannot be indexed without stating it. It matches `nomic-embed-text`, which is what a local
-- Ollama offers; a model of another width is refused at the seam with a sentence naming both
-- numbers, because a silently dropped vector would look exactly like a model nobody
-- configured.
--
-- `embedding_model` is stored beside it for a harder reason: two vectors from two different
-- models are not comparable at all, and the cosine between them is a number rather than an
-- error. A row whose model is not the configured one is re-embedded rather than compared.
ALTER TABLE offer
    ADD COLUMN embedding       vector(768),
    ADD COLUMN embedding_model TEXT;

-- HNSW rather than IVFFlat: IVFFlat has to be built on a populated table to choose its lists,
-- and this column starts empty on every existing installation. The operator class is the one
-- the strategies name -- cosine -- and an index built for a different distance is not used at
-- all rather than used badly.
CREATE INDEX idx_offer_embedding ON offer USING hnsw (embedding vector_cosine_ops);
