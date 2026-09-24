// SPDX-License-Identifier: Apache-2.0
//
// Copyright 2026 Marcello Muscara (codeministry)
//
// Licensed under the Apache License, Version 2.0. You may obtain a copy of the
// License at http://www.apache.org/licenses/LICENSE-2.0

// Which writing model writes the better cover letter, decided blind.
//
// A letter is not a number, so this script does not score one. It collects what each candidate
// model drafts for the same packaged offers, counts what the guard sent back to the template
// and how long each draft took, and renders the letters side by side under neutral labels. The
// operator ranks them without knowing which model wrote which; the key is a separate file.
//
// One pass per candidate, because the running instance reads `LLM_MODEL_WRITING` from `.env`:
//
//   # set LLM_MODEL_WRITING=<candidate> in .env, restart the API, then:
//   bun docs/samples/measure_cover_letters.ts draft --label=a 101 102 103 104 105
//   # next candidate, same offers:
//   bun docs/samples/measure_cover_letters.ts draft --label=b 101 102 103 104 105
//   bun docs/samples/measure_cover_letters.ts sheet
//
// `draft` calls POST /api/v1/offers/{id}/cover-letter/draft, which replaces the offer's stored
// letter. An offer whose letter was edited by hand is refused before anything is sent, so the
// measurement never destroys the operator's own work. No model name is written by this script:
// the label is whatever the operator chose, and the mapping from label to model stays in their
// head or their notes.
//
// Everything it writes names real adverts and the operator's letters, so it is gitignored.
//
// Reads LEADGEN_API (default http://localhost:8080) and, when the API asks for one,
// LEADGEN_TOKEN as a bearer token.

import { mkdirSync, readdirSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { join } from "node:path";

const API = (process.env.LEADGEN_API ?? "http://localhost:8080").replace(/\/$/, "");
const TOKEN = process.env.LEADGEN_TOKEN;
const OUT = join(import.meta.dir, "cover-letters");

type CoverLetterView = { text: string; author: "model" | "template" | "edited"; at: string };
type Drafted = { offer: number; author: CoverLetterView["author"] | "refused"; ms: number; text: string; note?: string };

function headers(): Record<string, string> {
  return {
    "Content-Type": "application/json",
    ...(TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {}),
  };
}

async function current(offer: number): Promise<CoverLetterView | null> {
  const response = await fetch(`${API}/api/v1/offers/${offer}/cover-letter`, { headers: headers() });
  return response.ok ? ((await response.json()) as CoverLetterView) : null;
}

async function draft(label: string, offers: number[]): Promise<void> {
  if (!/^[a-z0-9-]+$/.test(label)) {
    throw new Error(`--label must be lower-case letters, digits or dashes, got "${label}"`);
  }
  const results: Drafted[] = [];
  for (const offer of offers) {
    const before = await current(offer);
    if (before === null) {
      results.push({ offer, author: "refused", ms: 0, text: "", note: "no package" });
      continue;
    }
    if (before.author === "edited") {
      results.push({ offer, author: "refused", ms: 0, text: "", note: "edited by hand, left alone" });
      continue;
    }
    const started = performance.now();
    const response = await fetch(`${API}/api/v1/offers/${offer}/cover-letter/draft`, {
      method: "POST",
      headers: headers(),
    });
    const ms = Math.round(performance.now() - started);
    if (!response.ok) {
      results.push({ offer, author: "refused", ms, text: "", note: `HTTP ${response.status}` });
      continue;
    }
    const view = (await response.json()) as CoverLetterView;
    results.push({ offer, author: view.author, ms, text: view.text });
    console.log(`${offer}\t${view.author}\t${ms} ms`);
  }
  mkdirSync(OUT, { recursive: true });
  writeFileSync(join(OUT, `${label}.json`), JSON.stringify(results, null, 2));

  const drafted = results.filter((r) => r.author !== "refused");
  const byModel = drafted.filter((r) => r.author === "model").length;
  const median = drafted.map((r) => r.ms).sort((a, b) => a - b)[Math.floor(drafted.length / 2)] ?? 0;
  console.log(
    `\n${label}: ${byModel}/${drafted.length} written by the model, ` +
      `${drafted.length - byModel} fell back to the template, median ${median} ms; ` +
      `${results.length - drafted.length} refused`,
  );
}

function shuffled<T>(items: T[]): T[] {
  const out = [...items];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

function sheet(): void {
  if (!existsSync(OUT)) {
    throw new Error(`nothing drafted yet: ${OUT} does not exist`);
  }
  const runs = readdirSync(OUT)
    .filter((f) => f.endsWith(".json") && f !== "key.json")
    .map((f) => ({ label: f.slice(0, -5), letters: JSON.parse(readFileSync(join(OUT, f), "utf8")) as Drafted[] }));
  const offers = [...new Set(runs.flatMap((r) => r.letters.map((l) => l.offer)))].sort((a, b) => a - b);

  const key: Record<string, Record<string, string>> = {};
  const lines: string[] = ["# Cover letters, blind", "", "Rank the letters per offer, best first. The key is in key.json.", ""];
  for (const offer of offers) {
    const candidates = shuffled(
      runs.flatMap((r) => r.letters.filter((l) => l.offer === offer && l.author !== "refused").map((l) => ({ ...l, label: r.label }))),
    );
    key[offer] = {};
    lines.push(`## Offer ${offer}`, "");
    candidates.forEach((c, i) => {
      const letter = String.fromCharCode(65 + i);
      key[offer][letter] = `${c.label} (${c.author})`;
      lines.push(`### ${letter}`, "", "```text", c.text.trimEnd(), "```", "");
    });
  }
  writeFileSync(join(OUT, "sheet.md"), lines.join("\n"));
  writeFileSync(join(OUT, "key.json"), JSON.stringify(key, null, 2));
  console.log(`wrote ${join(OUT, "sheet.md")} and key.json for ${offers.length} offers, ${runs.length} candidates`);
}

const [mode, ...rest] = process.argv.slice(2);
if (mode === "draft") {
  const label = rest.find((a) => a.startsWith("--label="))?.slice("--label=".length);
  const offers = rest.filter((a) => !a.startsWith("--")).map(Number);
  if (!label || offers.length === 0 || offers.some(Number.isNaN)) {
    throw new Error("usage: draft --label=<label> <offer id> [<offer id> …]");
  }
  await draft(label, offers);
} else if (mode === "sheet") {
  sheet();
} else {
  throw new Error("usage: measure_cover_letters.ts draft --label=<label> <ids…> | sheet");
}
