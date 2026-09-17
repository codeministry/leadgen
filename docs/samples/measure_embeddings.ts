// SPDX-License-Identifier: Apache-2.0
//
// Copyright 2026 Marcello Muscara (codeministry)
//
// Licensed under the Apache License, Version 2.0. You may obtain a copy of the
// License at http://www.apache.org/licenses/LICENSE-2.0

// What the configured embedding model actually does to this corpus, measured rather than
// assumed. `matching-rules.yaml` merges two adverts above a cosine similarity and flags them
// above a lower one; those numbers shipped before anything read them, and a merge hides an
// offer behind another one, so they are worth a look before they act.
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
// truncated. Vectors are cached per model and per text mode beside this file, so a second run
// is free.
//
// ## The two text modes
//
//   --text=teaser   `OfferEmbedder.text()`: title, location, the advert's opening. The default,
//                   and what the dedupe column holds.
//   --text=advert   what a retrieval vector would hold: title, location, and the de-furnitured
//                   advert as `ContentText.of` builds it.
//   --offers=NAME   read a different input file beside this one, default `offers.json`.
//
// **Run both modes over one input file, never one mode over each.** The question is what the
// longer text does to the same adverts, and two files are two populations: pair counts scale
// with the square of the row count, so a band table over one corpus and a band table over
// another are not comparable even when both are correct. `docs/decisions/retrieval.md` says why
// this matters.
//
// The output names real adverts, so it is gitignored with everything else derived from the
// newsletter. Read it; a table of similarities nobody can check against the titles behind
// them is a table nobody should act on.
//
// Reads LLM_BASE_URL, defaulting to a local Ollama. The request is the OpenAI `/embeddings`
// shape, which is the one `EmbeddingModels` uses for both providers it supports.

const BASE = process.env.LLM_BASE_URL ?? "http://localhost:11434/v1";
const positional = process.argv.slice(2).filter((a) => !a.startsWith("--"));
const flag = (name: string) =>
  process.argv.slice(2).find((a) => a.startsWith(`--${name}=`))?.split("=", 2)[1];

const MODEL = positional[0] ?? "nomic-embed-text";
const TRUNCATE = positional[1] ? Number(positional[1]) : 0;
const MODE = flag("text") ?? "teaser";
const OFFERS_FILE = flag("offers") ?? "offers.json";
if (MODE !== "teaser" && MODE !== "advert") {
  throw new Error(`--text is 'teaser' or 'advert', not '${MODE}'`);
}

const BATCH = 32; // OfferEmbedder's batch, so one run here is one run there.
const DESCRIPTION_CHARS = 600; // OfferEmbedder's cap on the teaser.
// The retrieval cap. Measured 2026-09-17: `qwen3-embedding:8b` does not truncate its input at
// any length up to 24000 characters, so this number is not a server limit. It is a choice about
// dilution — the same run showed two texts that differ only in what the job is, and agree on
// everything around it, moving from 0.49 apart at teaser length to 0.91 alike at 6000.
const ADVERT_CHARS = 6000;
const FLOOR = 0.8; // Below the flag threshold; anything under it cannot act.
const LISTED = 400;

type Offer = {
  id: number;
  title: string;
  location: string;
  description: string;
  full_text?: string;
  content_blocks?: string;
};

const offers: Offer[] = JSON.parse(await Bun.file(`${import.meta.dir}/${OFFERS_FILE}`).text());

/** OfferEmbedder.text(), character for character: title, location, the advert's opening. */
function teaserText(offer: Offer): string {
  let built = (offer.title ?? "").trim();
  if (offer.location?.trim()) built += "\n" + offer.location.trim();
  if (offer.description?.trim()) {
    const opening = offer.description.trim();
    built += "\n" + (opening.length <= DESCRIPTION_CHARS ? opening : opening.slice(0, DESCRIPTION_CHARS));
  }
  return built;
}

/**
 * `ContentText.of(content_blocks, full_text)`: the CONTENT blocks joined, or the whole page when
 * the advert was never segmented or every block turned out to be furniture. The fallback is the
 * part worth copying exactly — without it an unsegmented advert contributes nothing and the
 * measurement quietly describes a smaller corpus than it claims to.
 */
function contentText(offer: Offer): string {
  const raw = offer.content_blocks?.trim();
  if (raw) {
    try {
      const blocks = JSON.parse(raw) as { kind: string; text: string }[];
      const joined = blocks
        .filter((block) => block.kind === "CONTENT")
        .map((block) => block.text)
        .join("\n\n")
        .trim();
      if (joined) return joined;
    } catch {
      // A column that is not an array is not a reason to stop the run; the fallback below is
      // the same one the application takes.
    }
  }
  return (offer.full_text ?? "").trim();
}

/** What a retrieval vector would hold. Mirrors the `AdvertText` the plan describes. */
function advertText(offer: Offer): string {
  let built = (offer.title ?? "").trim();
  if (offer.location?.trim()) built += "\n" + offer.location.trim();
  const advert = contentText(offer);
  if (advert) {
    built += "\n" + (advert.length <= ADVERT_CHARS ? advert : advert.slice(0, ADVERT_CHARS));
  }
  return built;
}

const text = MODE === "advert" ? advertText : teaserText;

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

// The cache is keyed by what went into it, which is the model, the text mode **and** the input
// file. One cache name per model was right while there was one text; a second text under the
// same name would be read back as the first, and the run would report a corpus it never
// embedded — with no error anywhere, because a vector of the right width is always plausible.
// The legacy name is kept for exactly the combination that wrote it, so the existing cache and
// the band table it produced stay reproducible.
const legacy = MODE === "teaser" && OFFERS_FILE === "offers.json";
const tag = legacy ? slug : `${slug}-${MODE}-${OFFERS_FILE.replace(/\.json$/, "")}`;
const cachePath = `${import.meta.dir}/embedding-cache-${tag}.json`;

let vectors: number[][];
if (await Bun.file(cachePath).exists()) {
  vectors = JSON.parse(await Bun.file(cachePath).text());
  console.error(`cache hit: ${vectors.length} vectors from ${cachePath}`);
  if (vectors.length !== offers.length) {
    throw new Error(
      `the cache holds ${vectors.length} vectors and ${OFFERS_FILE} has ${offers.length} offers;` +
        ` delete ${cachePath} and run again`,
    );
  }
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
const lengths = offers.map((offer) => text(offer).length);
const meanLength = Math.round(lengths.reduce((sum, n) => sum + n, 0) / lengths.length);
console.error(`model=${MODEL} mode=${MODE} dims=${dimensions} rows=${vectors.length} mean chars=${meanLength}`);

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

// Every pair, in 0.002-wide buckets. The band table above the floor is sparse by design — it
// counts what could act — and on a few hundred rows it is too sparse to say anything about the
// corpus as a whole, because pair counts fall with the square of the row count. The histogram
// is the dense half of the same measurement, and it is what two text modes are compared on.
const BUCKET = 0.002;
const histogram = new Int32Array(Math.ceil(2 / BUCKET) + 1);
let total = 0;

for (let a = 0; a < count; a++) {
  for (let b = a + 1; b < count; b++) {
    let similarity = 0;
    const offsetA = a * dimensions;
    const offsetB = b * dimensions;
    for (let d = 0; d < dimensions; d++) similarity += unit[offsetA + d] * unit[offsetB + d];
    histogram[Math.min(histogram.length - 1, Math.max(0, Math.round((similarity + 1) / BUCKET)))]++;
    total++;
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

/** The similarity below which `share` of all pairs sit. */
function percentile(share: number): number {
  let seen = 0;
  const target = share * total;
  for (let bucket = 0; bucket < histogram.length; bucket++) {
    seen += histogram[bucket];
    if (seen >= target) return bucket * BUCKET - 1;
  }
  return 1;
}

const PERCENTILES = [0.5, 0.9, 0.99, 0.999, 0.9999];

const cell = (value: string) => value.replace(/\|/g, "\\|").replace(/\n/g, " ").slice(0, 110);
const lines = [
  `# Observed pair similarities — \`${MODEL}\`, ${MODE} text (${dimensions} dims${TRUNCATE > 0 ? ", truncated and renormalised" : ""})`,
  "",
  `Population: ${count} offers from \`${OFFERS_FILE}\`. Text built by ` +
    (MODE === "advert"
      ? "`AdvertText`: title, location, and `ContentText.of(content_blocks, full_text)` capped at " +
        `${ADVERT_CHARS} characters.`
      : "`OfferEmbedder.text()`: title, location, and the advert's opening capped at " +
        `${DESCRIPTION_CHARS} characters.`),
  `Mean input length: ${meanLength} characters. Each pair is listed once.`,
  "",
  "## The whole distribution",
  "",
  "Every pair, not only the ones that could act. **This is the half to compare across text",
  "modes**: the band table below counts a tail whose size falls with the square of the row",
  "count, so it is not comparable between two corpora of different sizes and the percentiles are.",
  "",
  "| percentile | similarity |",
  "|---|---|",
  ...PERCENTILES.map((p) => `| p${Number((p * 100).toFixed(2))} | ${percentile(p).toFixed(4)} |`),
  "",
  `Pairs compared: ${total}.`,
  "",
  "## The bands that can act",
  "",
  "| band | pairs |",
  "|---|---|",
  ...EDGES.map((edge) => `| >= ${edge} | ${bands.get(edge) ?? 0} |`),
  "",
  `At or above ${FLOOR}: **${pairs.length}** of ${total} possible pairs.`,
  "",
  "## The pairs, strongest first",
  "",
  "| similarity | title A | title B |",
  "|---|---|---|",
  ...pairs.slice(0, LISTED).map((p) => `| ${p.similarity.toFixed(4)} | ${cell(offers[p.a].title)} | ${cell(offers[p.b].title)} |`),
];
if (pairs.length > LISTED) lines.push("", `_${pairs.length - LISTED} further pairs down to ${FLOOR}, omitted._`);

const name = `embedding-observed-${tag}${TRUNCATE > 0 ? `-${dimensions}d` : ""}.md`;
await Bun.write(`${import.meta.dir}/${name}`, lines.join("\n") + "\n");
console.error(`written docs/samples/${name}`);
