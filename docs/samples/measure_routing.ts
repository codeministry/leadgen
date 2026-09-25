// SPDX-License-Identifier: Apache-2.0
//
// Copyright 2026 Marcello Muscara (codeministry)
//
// Licensed under the Apache License, Version 2.0. You may obtain a copy of the
// License at http://www.apache.org/licenses/LICENSE-2.0

// Could a smaller model answer a bounded question as well as the configured one?
//
// Three stages ask a model a question with a closed answer: which kind each block of an advert
// is (blocks), when it starts, how long it runs and when to apply (fields), and how many points
// each judge factor gives (judge). For adverts the current configuration already answered, this
// script asks each candidate the same question again and compares the answer with the stored
// one. It prints one table per question, one row per candidate: how many adverts were compared,
// the agreement, the parts behind it, how many calls brought no answer and how many brought a
// reply nobody could read, p50 and p95 latency, and a verdict.
//
// Agreement is built so that saying nothing cannot win it. Most blocks are the advert, most
// fields are not stated and most factors give 0 points, so a plain share of equal answers would
// reward a candidate that answers the empty shape every time. Instead:
//
//   blocks  recall per kind: of the stored blocks of one kind, the share the candidate labelled
//           the same (a block it left out reads as CONTENT). Agreement is the mean over the
//           kinds present in the stored answers, one column each.
//   fields  per field, "stated" (the stored value is set: the candidate says the same) apart
//           from "silent" (the stored value is null: the candidate also says null). Agreement is
//           the mean of the stated shares; a field never stated in the sample is left out and
//           shown "-". The silent column pools the three fields, to show invented values.
//   judge   per factor, agreement only over the adverts where the stored or the candidate points
//           are non-zero. Agreement is the mean over the factors that had any such advert; mad,
//           the mean absolute points difference over every advert and factor, stays beside it.
//
// Before the candidates each table prints a computed row, "empty answer": the same formulas
// applied to the empty reading (every block CONTENT, every field null, every factor 0) against
// the stored answers the candidates were compared with. It is where silence lands, and the floor
// is read against it: for blocks 1 / the number of kinds present (CONTENT's recall), for fields
// and judge 0.
//
// The verdict is `too few` when fewer than 20 adverts were compared in that row (after skips or
// an early stop), with its numbers still shown; otherwise `refused` below the floor, `ok` at or
// above it. Latency counts only the calls that brought an answer; the others are counted already.
//
//   bun docs/samples/measure_routing.ts --models=<a>,<b> [--questions=blocks,fields,judge] \
//       [--floor=0.8] 101 102 103 …
//   bun docs/samples/measure_routing.ts --models=<a>,<b> --ids-file=ids.txt
//
// The ids come from the arguments, from a file (whitespace- or comma-separated), or both. A
// sample under 20 adverts is refused: agreement over fewer is noise. A candidate must be one
// of the models the configuration names (`scoring`, `scoring_options`, `content`, `fields`,
// `extraction`), otherwise the API answers 400 and the run stops. The floor of 0.8 is a
// chosen default, not a measured one.
//
// Each call is POST /api/v1/offers/{id}/answer?question=…&model=…, one per advert, candidate
// and question, in sequence. It changes nothing but the day's LLM budget counter. An advert
// with nothing to compare (409: no fetched text, no stored answer) or gone (404) is skipped
// and counted. A spent budget (429) stops the whole run at once; what was measured so far is
// still printed, with a line saying why it stopped, and the exit code is 2. Any other error once
// the first call has gone out (400 unknown model, 5xx, the network) prints the same partial
// tables, then the sentence, and exits 1. A refusal before the first call prints no table.
//
// No model name is written into this script: candidates are whatever the operator passes.
// It writes no file.
//
// Reads LEADGEN_API (default http://localhost:8080) and, when the API asks for one,
// LEADGEN_TOKEN as a bearer token.

import { readFileSync } from "node:fs";

export type Question = "blocks" | "fields" | "judge";

export const QUESTIONS: Question[] = ["blocks", "fields", "judge"];
export const MIN_SAMPLE = 20;
export const DEFAULT_FLOOR = 0.8;

const FIELDS = ["start", "duration", "deadline"] as const;
const FACTORS = ["role_fit", "stack_mismatch_dominant", "role_mismatch", "vague_description"] as const;

type Block = { index: number; kind: string };
type BlocksAnswer = { blocks: Block[] };
type FieldsAnswer = {
  start: { text: string; date: string | null } | null;
  duration: { text: string; months: number | null } | null;
  deadline: { text: string; date: string | null } | null;
};
type JudgeAnswer = { reasons: { factor: string; points: number }[] };
type Answered = { answer: unknown; stored: unknown; raw?: string | null; millis: number };

export type Options = {
  api: string;
  token?: string;
  ids: number[];
  models: string[];
  questions: Question[];
  floor: number;
};

export type Row = {
  candidate: string;
  /** Adverts compared. */
  n: number;
  skipped: number;
  agreement: number;
  /**
   * Per-part numbers: block count and recall per kind; the stated share per field and the pooled
   * silent share; the non-zero agreement per factor and the mean absolute difference; plus the
   * counts "no answer" and "unreadable".
   */
  parts: Record<string, number>;
  /** Over answered calls only. */
  p50: number;
  p95: number;
  verdict: "ok" | "refused" | "too few" | "no data";
};

export type Report = {
  floor: number;
  tables: Record<Question, Row[]>;
  /** Per question, the empty reading scored against the stored answers the candidates were compared with. */
  baselines: Partial<Record<Question, Row>>;
  stoppedOnBudget: boolean;
  /** A 400, a 5xx or a network error after the first call: the tables hold what came before it. */
  stoppedOnError: boolean;
  stopReason?: string;
};

export class SampleTooSmall extends Error {}

class BudgetSpent extends Error {}

/** Nearest-rank percentile; NaN on an empty list. */
function percentile(values: number[], p: number): number {
  if (values.length === 0) return NaN;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil(p * sorted.length) - 1)];
}

const share = (hits: number, total: number) => (total === 0 ? NaN : hits / total);

/** Mean of the numbers that are not NaN; NaN when none is. */
function mean(values: number[]): number {
  const real = values.filter((v) => !Number.isNaN(v));
  return real.length === 0 ? NaN : real.reduce((sum, v) => sum + v, 0) / real.length;
}

/** The kind an unlabelled block reads as: the advert itself. */
const ADVERT = "CONTENT";

function blocksRow(answers: Answered[]): { agreement: number; parts: Record<string, number> } {
  const total = new Map<string, number>();
  const hits = new Map<string, number>();
  let blocks = 0;
  for (const { answer, stored } of answers) {
    const said = new Map((answer as BlocksAnswer).blocks.map((b) => [b.index, b.kind]));
    for (const block of (stored as BlocksAnswer).blocks) {
      blocks += 1;
      total.set(block.kind, (total.get(block.kind) ?? 0) + 1);
      if ((said.get(block.index) ?? ADVERT) === block.kind) hits.set(block.kind, (hits.get(block.kind) ?? 0) + 1);
    }
  }
  const parts: Record<string, number> = { blocks };
  for (const [kind, count] of total) parts[kind] = share(hits.get(kind) ?? 0, count);
  return { agreement: mean([...total.keys()].map((kind) => parts[kind])), parts };
}

function fieldValue(answer: FieldsAnswer, field: (typeof FIELDS)[number]): string | number | null {
  const part = answer[field];
  if (!part) return null;
  return field === "duration" ? (part as { months: number | null }).months : (part as { date: string | null }).date;
}

function fieldsRow(answers: Answered[]): { agreement: number; parts: Record<string, number> } {
  const parts: Record<string, number> = {};
  let silent = 0;
  let silentKept = 0;
  for (const field of FIELDS) {
    let stated = 0;
    let statedHits = 0;
    for (const { answer, stored } of answers) {
      const want = fieldValue(stored as FieldsAnswer, field);
      const said = fieldValue(answer as FieldsAnswer, field);
      if (want == null) {
        silent += 1;
        if (said == null) silentKept += 1;
      } else {
        stated += 1;
        if (said === want) statedHits += 1;
      }
    }
    parts[field] = share(statedHits, stated);
  }
  parts.silent = share(silentKept, silent);
  return { agreement: mean(FIELDS.map((field) => parts[field])), parts };
}

function points(answer: JudgeAnswer, factor: string): number {
  return answer.reasons.find((r) => r.factor === factor)?.points ?? 0;
}

function judgeRow(answers: Answered[]): { agreement: number; parts: Record<string, number> } {
  const parts: Record<string, number> = {};
  let difference = 0;
  for (const factor of FACTORS) {
    let cases = 0;
    let hits = 0;
    for (const { answer, stored } of answers) {
      const said = points(answer as JudgeAnswer, factor);
      const want = points(stored as JudgeAnswer, factor);
      difference += Math.abs(said - want);
      if (said === 0 && want === 0) continue;
      cases += 1;
      if (said === want) hits += 1;
    }
    parts[factor] = share(hits, cases);
  }
  const agreement = mean(FACTORS.map((factor) => parts[factor]));
  parts.mad = share(difference, answers.length * FACTORS.length);
  return { agreement, parts };
}

const SCORE: Record<Question, (answers: Answered[]) => { agreement: number; parts: Record<string, number> }> = {
  blocks: blocksRow,
  fields: fieldsRow,
  judge: judgeRow,
};

// What an answer that never arrived counts as, and what the "empty answer" row answers: the empty
// reading of its question, so it disagrees wherever the stored answer said something. For blocks
// the empty list reads as every block CONTENT, since an unlabelled block is the advert. The server answers null only when no reply came back
// at all (a transport failure after the budget was taken); an unreadable reply already arrives as
// this empty shape, with `raw` beside it.
const NOTHING: Record<Question, unknown> = {
  blocks: { blocks: [] },
  fields: { start: null, duration: null, deadline: null },
  judge: { reasons: [] },
};

/** Whether a parsed answer is its question's empty shape. */
function isEmpty(question: Question, answer: unknown): boolean {
  switch (question) {
    case "blocks":
      return (answer as BlocksAnswer).blocks.every((b) => b.kind === ADVERT);
    case "fields":
      return FIELDS.every((field) => (answer as FieldsAnswer)[field] == null);
    case "judge":
      return (answer as JudgeAnswer).reasons.every((r) => r.points === 0);
  }
}

function verdictOf(n: number, agreement: number, floor: number): Row["verdict"] {
  if (n < MIN_SAMPLE) return "too few";
  if (Number.isNaN(agreement)) return "no data";
  return agreement < floor ? "refused" : "ok";
}

function row(question: Question, candidate: string, answered: Answered[], skipped: number, floor: number): Row {
  const unanswered = answered.filter((a) => a.answer == null).length;
  // The server parses an unreadable reply into the empty shape and keeps the reply in raw.
  const unreadable = answered.filter(
    (a) => a.answer != null && isEmpty(question, a.answer) && typeof a.raw === "string" && a.raw.trim() !== "",
  ).length;
  const answers = answered.map((a) => (a.answer == null ? { ...a, answer: NOTHING[question] } : a));
  const scored = SCORE[question](answers);
  const agreement = scored.agreement;
  const parts = { ...scored.parts, "no answer": unanswered, unreadable };
  const millis = answered.filter((a) => a.answer != null).map((a) => a.millis);
  return {
    candidate,
    n: answers.length,
    skipped,
    agreement,
    parts,
    p50: percentile(millis, 0.5),
    p95: percentile(millis, 0.95),
    verdict: verdictOf(answers.length, agreement, floor),
  };
}

/** The empty reading scored against every stored answer the candidates of one question were compared with. */
function baseline(question: Question, stored: Map<number, unknown>, floor: number): Row {
  const answers = [...stored.values()].map((s) => ({ answer: NOTHING[question], stored: s, millis: NaN }));
  const scored = SCORE[question](answers);
  return {
    candidate: "empty answer",
    n: answers.length,
    skipped: NaN,
    agreement: scored.agreement,
    parts: { ...scored.parts, "no answer": NaN, unreadable: NaN },
    p50: NaN,
    p95: NaN,
    verdict: verdictOf(answers.length, scored.agreement, floor),
  };
}

async function detail(response: Response): Promise<string> {
  const text = await response.text();
  try {
    const body = JSON.parse(text) as { detail?: string; message?: string };
    return body.detail ?? body.message ?? text;
  } catch {
    return text;
  }
}

/** One call; null when the advert is to be skipped for this question. */
async function ask(options: Options, id: number, question: Question, model: string): Promise<Answered | null> {
  const url = `${options.api.replace(/\/$/, "")}/api/v1/offers/${id}/answer?question=${question}&model=${encodeURIComponent(model)}`;
  let response: Response;
  try {
    response = await fetch(url, {
      method: "POST",
      headers: options.token ? { Authorization: `Bearer ${options.token}` } : {},
    });
  } catch (error) {
    throw new Error(`offer ${id}, ${question}, ${model}: ${(error as Error).message}`);
  }
  if (response.ok) return (await response.json()) as Answered;
  if (response.status === 404 || response.status === 409) return null;
  const sentence = await detail(response);
  if (response.status === 429) throw new BudgetSpent(sentence || "today's llm.budget is spent");
  throw new Error(`offer ${id}, ${question}, ${model}: HTTP ${response.status} ${sentence}`);
}

/** Asks every candidate every question for every advert, in sequence, and scores the answers. */
export async function run(options: Options): Promise<Report> {
  const ids = [...new Set(options.ids)];
  if (ids.length < MIN_SAMPLE) {
    throw new SampleTooSmall(
      `${ids.length} adverts is too few to measure agreement: give at least ${MIN_SAMPLE}.`,
    );
  }
  if (options.models.length === 0) throw new Error("no candidate: pass --models=<a>,<b>.");
  const report: Report = {
    floor: options.floor,
    tables: { blocks: [], fields: [], judge: [] },
    baselines: {},
    stoppedOnBudget: false,
    stoppedOnError: false,
  };
  let calls = 0;
  for (const question of options.questions) {
    const stored = new Map<number, unknown>();
    for (const model of options.models) {
      const answers: Answered[] = [];
      let skipped = 0;
      try {
        for (const id of ids) {
          calls += 1;
          const answered = await ask(options, id, question, model);
          if (answered) {
            answers.push(answered);
            stored.set(id, answered.stored);
          } else skipped += 1;
        }
      } catch (error) {
        if (error instanceof BudgetSpent) report.stoppedOnBudget = true;
        else if (calls > 1) report.stoppedOnError = true;
        else throw error;
        report.stopReason = (error as Error).message;
      }
      report.tables[question].push(row(question, model, answers, skipped, options.floor));
      report.baselines[question] = baseline(question, stored, options.floor);
      if (report.stoppedOnBudget || report.stoppedOnError) return report;
    }
  }
  return report;
}

const COUNTS = ["no answer", "unreadable"];

/** The part columns of one table. Blocks name the kinds the stored answers held, CONTENT first. */
function partColumns(question: Question, rows: Row[]): string[] {
  if (question === "fields") return [...FIELDS, "silent", ...COUNTS];
  if (question === "judge") return [...FACTORS, "mad", ...COUNTS];
  const kinds = new Set(rows.flatMap((r) => Object.keys(r.parts)));
  for (const fixed of ["blocks", ...COUNTS]) kinds.delete(fixed);
  const sorted = [...kinds].sort((a, b) => (a === ADVERT ? -1 : b === ADVERT ? 1 : a.localeCompare(b)));
  return ["blocks", ...sorted, ...COUNTS];
}

function cell(value: number | undefined, kind: "share" | "count" | "ms" | "diff"): string {
  if (value === undefined || Number.isNaN(value)) return "-";
  if (kind === "share") return `${(value * 100).toFixed(1)} %`;
  if (kind === "diff") return value.toFixed(2);
  return String(Math.round(value));
}

/** The tables as plain text, one per question, plus the reason when the run stopped early. */
export function render(report: Report): string {
  const out: string[] = [];
  for (const question of QUESTIONS) {
    const candidates = report.tables[question];
    if (candidates.length === 0) continue;
    const empty = report.baselines[question];
    const rows = empty ? [empty, ...candidates] : candidates;
    const parts = partColumns(question, rows);
    const header = ["candidate", "n", "skipped", "agreement", ...parts, "p50 ms", "p95 ms", "verdict"];
    const lines = rows.map((r) => [
      r.candidate,
      String(r.n),
      cell(r.skipped, "count"),
      cell(r.agreement, "share"),
      ...parts.map((p) => cell(r.parts[p], p === "blocks" || COUNTS.includes(p) ? "count" : p === "mad" ? "diff" : "share")),
      cell(r.p50, "ms"),
      cell(r.p95, "ms"),
      r.verdict,
    ]);
    const widths = header.map((h, i) => Math.max(h.length, ...lines.map((l) => l[i].length)));
    const format = (cells: string[]) => cells.map((c, i) => c.padEnd(widths[i])).join("  ").trimEnd();
    out.push(`${question} (floor ${(report.floor * 100).toFixed(0)} %)`);
    out.push(format(header));
    out.push(format(widths.map((w) => "-".repeat(w))));
    out.push(...lines.map(format));
    out.push("");
  }
  if (report.stoppedOnBudget) {
    out.push(`Stopped on the budget: ${report.stopReason}. The tables above hold what was measured before it.`);
  }
  if (report.stoppedOnError) {
    out.push(`Stopped on an error: ${report.stopReason}. The tables above hold what was measured before it.`);
  }
  return out.join("\n");
}

function parseArgs(argv: string[]): Options {
  const flags = new Map<string, string>();
  const ids: number[] = [];
  for (const arg of argv) {
    const flag = arg.match(/^--([a-z-]+)=(.*)$/);
    if (flag) flags.set(flag[1], flag[2]);
    else if (/^\d+$/.test(arg)) ids.push(Number(arg));
    else throw new Error(`not an advert id or a --flag=value: "${arg}"`);
  }
  const file = flags.get("ids-file");
  if (file) {
    for (const token of readFileSync(file, "utf8").split(/[\s,]+/)) {
      if (token === "") continue;
      if (!/^\d+$/.test(token)) throw new Error(`${file}: not an advert id: "${token}"`);
      ids.push(Number(token));
    }
  }
  const questions = (flags.get("questions") ?? QUESTIONS.join(",")).split(",").filter(Boolean);
  for (const q of questions) {
    if (!QUESTIONS.includes(q as Question)) throw new Error(`unknown question "${q}": use ${QUESTIONS.join(", ")}.`);
  }
  const floor = Number(flags.get("floor") ?? DEFAULT_FLOOR);
  if (!(floor >= 0 && floor <= 1)) throw new Error(`--floor must be between 0 and 1, got "${flags.get("floor")}"`);
  return {
    api: process.env.LEADGEN_API ?? "http://localhost:8080",
    token: process.env.LEADGEN_TOKEN,
    ids,
    models: (flags.get("models") ?? "").split(",").filter(Boolean),
    questions: questions as Question[],
    floor,
  };
}

if (import.meta.main) {
  try {
    const report = await run(parseArgs(process.argv.slice(2)));
    console.log(render(report));
    if (report.stoppedOnError) {
      console.error(report.stopReason);
      process.exit(1);
    }
    process.exit(report.stoppedOnBudget ? 2 : 0);
  } catch (error) {
    console.error((error as Error).message);
    process.exit(1);
  }
}
