// SPDX-License-Identifier: Apache-2.0
//
// Copyright 2026 Marcello Muscara (codeministry)
//
// Licensed under the Apache License, Version 2.0. You may obtain a copy of the
// License at http://www.apache.org/licenses/LICENSE-2.0

// Which reference projects a cover letter would pitch, per advert, three ways.
//
// A band table answers "how similar"; it never answers "is the right thing on top". Without
// this script, "better cover letters" is an opinion — so this one prints, for every advert,
// what the lexical rule picks today, what similarity alone would pick, and what the shipped
// blend picks, each by project title, so it can be argued with by hand.
//
// **The two numbers to read first** are printed at the top: how many adverts get fewer than
// two references under today's rule, and how many the blend still leaves short. The first is
// the defect; the difference is what this change bought.
//
//   psql -t -A -c "... full_text, content_blocks ..." > offers-advert.json   # see measure_embeddings.ts
//   bun docs/samples/measure_references.ts qwen3-embedding:8b
//
// The advert vectors come from `measure_embeddings.ts`'s cache when it is there, so a run
// after that one costs only the profile's own texts. The profile is individual, so it is read
// from `config/skill-profile.yaml` and the output names real adverts — both gitignored.
//
// Reads LLM_BASE_URL, defaulting to a local Ollama.

import { parse as parseYaml } from "yaml";

const BASE = process.env.LLM_BASE_URL ?? "http://localhost:11434/v1";
const MODEL = process.argv[2] ?? "qwen3-embedding:8b";
const OFFERS_FILE = process.argv[3] ?? "offers-advert.json";
const DIMENSIONS = 2000; // Vectors.DIMENSIONS
const ADVERT_CHARS = 6000; // AdvertText.ADVERT_CHARS
const REFERENCES = 2; // PackagingService.REFERENCES

type Offer = {
  id: number;
  title: string;
  location: string;
  description: string;
  full_text?: string;
  content_blocks?: string;
};

type Project = {
  id: string;
  title?: string;
  role?: string;
  stack?: string[];
  pitch_de?: string;
  pitch_en?: string;
};

/** `TextFold.fold`, character for character: NFKD, lowercase, marks dropped, ß to ss. */
function fold(text: string): string {
  return (text ?? "")
    .normalize("NFKD")
    .toLowerCase()
    .replace(/[̀-ͯ]/g, "")
    .replace(/ß/g, "ss")
    .replace(/[^a-z0-9]+/g, " ")
    .trim()
    .replace(/\s+/g, " ");
}

/** `TextFold.keyword`: the folded token on word boundaries, never a substring. */
function names(haystack: string, keyword: string): boolean {
  const folded = fold(keyword);
  if (folded === "") return false;
  const escaped = folded.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`(?<![a-z0-9])${escaped}(?![a-z0-9])`).test(haystack);
}

/** `ContentText.of`: the CONTENT blocks, or the whole page when there are none. */
function contentText(offer: Offer): string {
  const raw = offer.content_blocks?.trim();
  if (raw) {
    try {
      const blocks = JSON.parse(raw) as { kind: string; text: string }[];
      const joined = blocks.filter((b) => b.kind === "CONTENT").map((b) => b.text).join("\n\n").trim();
      if (joined) return joined;
    } catch {
      // Same fallback the application takes.
    }
  }
  return (offer.full_text ?? "").trim();
}

/** `AdvertText.of`, which is what the offer's stored vector was built from. */
function advertText(offer: Offer): string {
  let built = (offer.title ?? "").trim();
  if (offer.location?.trim()) built += "\n" + offer.location.trim();
  const advert = contentText(offer);
  if (advert) built += "\n" + (advert.length <= ADVERT_CHARS ? advert : advert.slice(0, ADVERT_CHARS));
  return built;
}

/** `PackagingService.haystack`: what the lexical rule reads. */
function haystack(offer: Offer): string {
  return fold(`${offer.title ?? ""} ${offer.description ?? ""} ${contentText(offer)}`);
}

/** `ProfileEmbeddings.text`, for the German pitch — this corpus is a German market. */
function projectText(project: Project): string {
  let text = (project.title ?? "").trim();
  if (project.role?.trim()) text += "\n" + project.role.trim();
  if (project.stack?.length) text += "\n" + project.stack.join(", ");
  if (project.pitch_de?.trim()) text += "\n" + project.pitch_de.trim();
  return text.trim();
}

async function embed(inputs: string[]): Promise<number[][]> {
  const response = await fetch(`${BASE}/embeddings`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ model: MODEL, input: inputs }),
  });
  if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
  const body = (await response.json()) as { data: { index: number; embedding: number[] }[] };
  return body.data.sort((a, b) => a.index - b.index).map((e) => e.embedding);
}

function narrowed(v: number[]): number[] {
  return v.length <= DIMENSIONS ? v : v.slice(0, DIMENSIONS);
}

function similarity(a: number[], b: number[]): number {
  const width = Math.min(a.length, b.length);
  let dot = 0;
  let na = 0;
  let nb = 0;
  for (let i = 0; i < width; i++) {
    dot += a[i]! * b[i]!;
    na += a[i]! * a[i]!;
    nb += b[i]! * b[i]!;
  }
  const norm = Math.sqrt(na) * Math.sqrt(nb);
  return norm === 0 ? 0 : dot / norm;
}

const offers: Offer[] = JSON.parse(await Bun.file(`${import.meta.dir}/${OFFERS_FILE}`).text());
const profile = parseYaml(await Bun.file(`${import.meta.dir}/../../config/skill-profile.yaml`).text()) as {
  reference_projects?: Project[];
};
const projects = profile.reference_projects ?? [];
if (projects.length === 0) throw new Error("config/skill-profile.yaml names no reference_projects");

const slug = MODEL.replace(/[^a-z0-9]/gi, "-");
const advertCache = `${import.meta.dir}/embedding-cache-${slug}-advert-${OFFERS_FILE.replace(/\.json$/, "")}.json`;
let advertVectors: number[][];
if (await Bun.file(advertCache).exists()) {
  advertVectors = JSON.parse(await Bun.file(advertCache).text()).map(narrowed);
  console.error(`cache hit: ${advertVectors.length} advert vectors`);
  if (advertVectors.length !== offers.length) {
    throw new Error(`the cache holds ${advertVectors.length} vectors and ${OFFERS_FILE} has ${offers.length}`);
  }
} else {
  console.error(`no advert cache; embedding ${offers.length} adverts (run measure_embeddings.ts first to reuse)`);
  advertVectors = [];
  for (let i = 0; i < offers.length; i += 32) {
    advertVectors.push(...(await embed(offers.slice(i, i + 32).map(advertText))).map(narrowed));
  }
}

console.error(`embedding ${projects.length} reference projects`);
const projectVectors = (await embed(projects.map(projectText))).map(narrowed);

const title = (p: Project) => p.title ?? p.id;
const rows: string[] = [];
let shortToday = 0;
let shortBlended = 0;
let changed = 0;

for (let index = 0; index < offers.length; index++) {
  const offer = offers[index]!;
  const hay = haystack(offer);
  const scored = projects.map((project, p) => ({
    project,
    overlap: (project.stack ?? []).filter((s) => names(hay, s)).length,
    similarity: similarity(advertVectors[index]!, projectVectors[p]!),
  }));

  const lexical = scored
    .filter((s) => s.overlap > 0)
    .sort((a, b) => b.overlap - a.overlap)
    .slice(0, REFERENCES);

  const semantic = [...scored].sort((a, b) => b.similarity - a.similarity).slice(0, REFERENCES);

  // `ReferenceRanking.choose`: overlap first, similarity within it, then the empty slots.
  const blended = scored
    .filter((s) => s.overlap > 0)
    .sort((a, b) => b.overlap - a.overlap || b.similarity - a.similarity)
    .slice(0, REFERENCES);
  if (blended.length < REFERENCES) {
    blended.push(
      ...scored
        .filter((s) => s.overlap === 0 && s.similarity > 0)
        .sort((a, b) => b.similarity - a.similarity)
        .slice(0, REFERENCES - blended.length),
    );
  }

  if (lexical.length < REFERENCES) shortToday++;
  if (blended.length < REFERENCES) shortBlended++;
  if (lexical.map((s) => s.project.id).join() !== blended.map((s) => s.project.id).join()) changed++;

  const cell = (list: typeof scored) =>
    list.length === 0 ? "_none_" : list.map((s) => title(s.project)).join(", ").replace(/\|/g, "\\|");
  rows.push(
    `| ${(offer.title ?? "").replace(/\|/g, "\\|").slice(0, 70)} | ${cell(lexical)} | ${cell(semantic)} | ${cell(blended)} |`,
  );
}

const lines = [
  `# Reference-project selection — \`${MODEL}\``,
  "",
  `Population: ${offers.length} adverts from \`${OFFERS_FILE}\`, ${projects.length} reference projects.`,
  "",
  "## The two numbers",
  "",
  "| | adverts |",
  "|---|---|",
  `| fewer than ${REFERENCES} references under today's lexical rule | **${shortToday}** |`,
  `| fewer than ${REFERENCES} after the blend | **${shortBlended}** |`,
  `| where the blend changed the choice at all | ${changed} |`,
  "",
  "The first number is the defect this change exists for: a cover letter went out pitching one",
  "project or none, and nothing said so. The second is what is left. The third is how often the",
  "letter reads differently at all — read some of those rows before trusting the first two.",
  "",
  "## Per advert",
  "",
  "**Read this against the titles.** A table of selections nobody checks is a table nobody should",
  "act on, which is the same rule the band tables carry.",
  "",
  "| advert | lexical (today) | similarity only | blend (shipped) |",
  "|---|---|---|---|",
  ...rows,
];

const name = `reference-selection-${slug}.md`;
await Bun.write(`${import.meta.dir}/${name}`, lines.join("\n") + "\n");
console.error(`written docs/samples/${name}`);
console.error(`short today: ${shortToday}, short after the blend: ${shortBlended}, changed: ${changed}`);
