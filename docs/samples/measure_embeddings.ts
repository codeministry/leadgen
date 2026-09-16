// SPDX-License-Identifier: Apache-2.0
//
// Copyright 2026 Marcello Muscara (codeministry)
//
// Licensed under the Apache License, Version 2.0. You may obtain a copy of the
// License at http://www.apache.org/licenses/LICENSE-2.0

// What the configured embedding model actually does to this corpus, measured rather than
// assumed. `matching-rules.yaml` merges two adverts above a cosine similarity of 0.92 and
// flags them above 0.85; those two numbers shipped before anything read them, and a merge
// hides an offer behind another one, so they are worth a look before they act.
//
// Two steps, the same shape as analyze_samples.py feeding simulate_filter.py:
//
//   psql -t -A -c "select json_agg(row_to_json(t)) from (
//        select id, title, coalesce(location,'') as location,
//               coalesce(left(description,900),'') as description
//          from offer
//         where ingested_at >= now() - interval '60 days'
//           and duplicate_of_id is null and status='PASSED' order by id) t;" > offers.json
//   bun docs/samples/measure_embeddings.ts nomic-embed-text
//
// A second argument truncates every vector to that many dimensions and renormalises. It is
// there for one question only: a model trained with Matryoshka representation learning keeps
// most of its separation in the leading dimensions, and pgvector indexes a `vector` column
// only up to 2000 of them — so a 4096-wide model is unusable as it stands and worth a look
// truncated. Vectors are cached per model beside this file, so a second run is free.
//
// The output names real adverts, so it is gitignored with everything else derived from the
// newsletter. Read it; a table of similarities nobody can check against the titles behind
// them is a table nobody should act on.
//
// Reads LLM_BASE_URL, defaulting to a local Ollama. The request is the OpenAI `/embeddings`
// shape, which is the one `EmbeddingModels` uses for both providers it supports.

const BASE = process.env.LLM_BASE_URL ?? "http://localhost:11434/v1";
const MODEL = process.argv[2] ?? "nomic-embed-text";
const TRUNCATE = process.argv[3] ? Number(process.argv[3]) : 0;
const BATCH = 32; // OfferEmbedder's batch, so one run here is one run there.
const DESCRIPTION_CHARS = 600;
const FLOOR = 0.8; // Below the flag threshold; anything under it cannot act.
const LISTED = 400;

type Offer = { id: number; title: string; location: string; description: string };

const offers: Offer[] = JSON.parse(await Bun.file(`${import.meta.dir}/offers.json`).text());

/** OfferEmbedder.text(), character for character: title, location, the advert's opening. */
function text(offer: Offer): string {
  let built = (offer.title ?? "").trim();
  if (offer.location?.trim()) built += "\n" + offer.location.trim();
  if (offer.description?.trim()) {
    const opening = offer.description.trim();
    built += "\n" + (opening.length <= DESCRIPTION_CHARS ? opening : opening.slice(0, DESCRIPTION_CHARS));
  }
  return built;
}

async function embed(inputs: string[]): Promise<number[][]> {
  const response = await fetch(`${BASE}/embeddings`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ model: MODEL, input: inputs }),
  });
  if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
  const body = (await response.json()) as { data: { index: number; embedding: number[] }[] };
  return body.data.sort((a, b) => a.index - b.index).map((entry) => entry.embedding);
}

const slug = MODEL.replace(/[^a-z0-9]/gi, "-");
const cachePath = `${import.meta.dir}/embedding-cache-${slug}.json`;
let vectors: number[][];
if (await Bun.file(cachePath).exists()) {
  vectors = JSON.parse(await Bun.file(cachePath).text());
  console.error(`cache hit: ${vectors.length} vectors from ${cachePath}`);
} else {
  const started = Date.now();
  vectors = [];
  for (let index = 0; index < offers.length; index += BATCH) {
    vectors.push(...(await embed(offers.slice(index, index + BATCH).map(text))));
    if ((index / BATCH) % 20 === 0) console.error(`  embedded ${index + BATCH}/${offers.length}`);
  }
  console.error(`embedded in ${((Date.now() - started) / 1000).toFixed(0)}s`);
  await Bun.write(cachePath, JSON.stringify(vectors));
}
if (TRUNCATE > 0 && TRUNCATE < vectors[0].length) {
  vectors = vectors.map((v) => v.slice(0, TRUNCATE));
}
const dimensions = vectors[0].length;
console.error(`model=${MODEL} dims=${dimensions} rows=${vectors.length}`);

// Normalised once, so cosine similarity is a dot product. `SimilarOffers` asks postgres for
// a distance and passes `1 - similarity` as its limit; this is the same number the other way
// round.
const count = vectors.length;
const unit = new Float32Array(count * dimensions);
for (let row = 0; row < count; row++) {
  let norm = 0;
  for (let d = 0; d < dimensions; d++) norm += vectors[row][d] * vectors[row][d];
  norm = Math.sqrt(norm) || 1;
  for (let d = 0; d < dimensions; d++) unit[row * dimensions + d] = vectors[row][d] / norm;
}

const EDGES = [0.99, 0.97, 0.95, 0.92, 0.9, 0.85, 0.8];
const bands = new Map<number, number>();
const pairs: { a: number; b: number; similarity: number }[] = [];
for (let a = 0; a < count; a++) {
  for (let b = a + 1; b < count; b++) {
    let similarity = 0;
    const offsetA = a * dimensions;
    const offsetB = b * dimensions;
    for (let d = 0; d < dimensions; d++) similarity += unit[offsetA + d] * unit[offsetB + d];
    if (similarity < FLOOR) continue;
    pairs.push({ a, b, similarity });
    for (const edge of EDGES) {
      if (similarity >= edge) {
        bands.set(edge, (bands.get(edge) ?? 0) + 1);
        break;
      }
    }
  }
  if (a % 500 === 0) console.error(`  compared ${a}/${count}`);
}
pairs.sort((left, right) => right.similarity - left.similarity);

const cell = (value: string) => value.replace(/\|/g, "\\|").replace(/\n/g, " ").slice(0, 110);
const lines = [
  `# Observed pair similarities — \`${MODEL}\` (${dimensions} dims${TRUNCATE > 0 ? ", truncated and renormalised" : ""})`,
  "",
  `Population: ${count} offers, the non-duplicate PASSED rows inside \`ttl_days\`, so exactly what`,
  "the similarity strategies would see after `exact_fingerprint`. Text built by `OfferEmbedder.text()`.",
  "Each pair is listed once. `matching-rules.yaml` merges at 0.92 and flags at 0.85.",
  "",
  "| band | pairs |",
  "|---|---|",
  ...EDGES.map((edge) => `| >= ${edge} | ${bands.get(edge) ?? 0} |`),
  "",
  `At or above ${FLOOR}: **${pairs.length}** of ${(count * (count - 1)) / 2} possible pairs.`,
  "",
  "## The pairs, strongest first",
  "",
  "| similarity | title A | title B |",
  "|---|---|---|",
  ...pairs.slice(0, LISTED).map((p) => `| ${p.similarity.toFixed(4)} | ${cell(offers[p.a].title)} | ${cell(offers[p.b].title)} |`),
];
if (pairs.length > LISTED) lines.push("", `_${pairs.length - LISTED} further pairs down to ${FLOOR}, omitted._`);

const name = `embedding-observed-${slug}${TRUNCATE > 0 ? `-${dimensions}d` : ""}.md`;
await Bun.write(`${import.meta.dir}/${name}`, lines.join("\n") + "\n");
console.error(`written docs/samples/${name}`);
