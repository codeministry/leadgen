// SPDX-License-Identifier: Apache-2.0
//
// Copyright 2026 Marcello Muscara (codeministry)
//
// Licensed under the Apache License, Version 2.0. You may obtain a copy of the
// License at http://www.apache.org/licenses/LICENSE-2.0

// The probe for measure_routing.ts: a stub API on a free port answers for three candidates, one
// that agrees with the stored answer everywhere, one that disagrees on every odd advert, and one
// that always answers the empty shape with a reply nobody could read.
//
//   bun test docs/samples/measure_routing.test.ts

import { afterEach, describe, expect, test } from "bun:test";
import { join } from "node:path";
import { render, run, type Question } from "./measure_routing";

const KINDS = ["CONTENT", "CHROME", "FORM", "TAXONOMY", "AGENCY", "LEGAL"];
const FACTORS = ["role_fit", "stack_mismatch_dominant", "role_mismatch", "vague_description"];
const IDS = Array.from({ length: 20 }, (_, i) => 101 + i);

/** The kind of block `index` of advert `id` in the stored answer. */
const kindOf = (id: number, index: number, heavy = false) =>
  heavy ? (index === 9 ? "CHROME" : "CONTENT") : KINDS[(id + index) % KINDS.length];

function stored(question: Question, id: number, heavy = false): unknown {
  switch (question) {
    case "blocks": {
      const count = heavy ? 10 : 4;
      return { blocks: Array.from({ length: count }, (_, index) => ({ index, kind: kindOf(id, index, heavy) })) };
    }
    case "fields":
      return {
        start: { text: "immediately", date: id % 3 === 0 ? null : "2026-10-01" },
        duration: { text: "six months", months: 6 },
        deadline: { text: "", date: null },
      };
    case "judge":
      return { reasons: FACTORS.map((factor, i) => ({ factor, points: (id + i) % 4 })) };
  }
}

// What the weak candidate says on an odd advert: every part changed.
function wrong(question: Question, id: number): unknown {
  switch (question) {
    case "blocks":
      return { blocks: [0, 1, 2, 3].map((index) => ({ index, kind: KINDS[(id + index + 1) % KINDS.length] })) };
    case "fields":
      return {
        start: { text: "immediately", date: id % 3 === 0 ? "2026-11-01" : null },
        duration: { text: "six months", months: 12 },
        deadline: { text: "by Friday", date: "2026-10-02" },
      };
    case "judge":
      return { reasons: FACTORS.map((factor, i) => ({ factor, points: ((id + i) % 4) + 1 })) };
  }
}

// The empty shape as the server sends it for a reply it could not read.
function empty(question: Question, id: number, heavy = false): unknown {
  switch (question) {
    case "blocks":
      return { blocks: (stored("blocks", id, heavy) as { blocks: { index: number }[] }).blocks.map(({ index }) => ({ index, kind: "CONTENT" })) };
    case "fields":
      return { start: null, duration: null, deadline: null };
    case "judge":
      return { reasons: FACTORS.map((factor) => ({ factor, points: 0 })) };
  }
}

type Stub = { url: string; calls: () => number; stop: () => void };

function stub(
  opts: {
    budgetAfter?: number;
    failAfter?: number;
    noStored?: { id: number; question: Question };
    noAnswer?: { id: number; question: Question };
    unreadable?: { id: number; question: Question };
    /** Stored blocks: ten per advert, nine CONTENT and one CHROME. */
    contentHeavy?: boolean;
  } = {},
): Stub {
  let calls = 0;
  const server = Bun.serve({
    port: 0,
    fetch(request) {
      const url = new URL(request.url);
      const match = url.pathname.match(/^\/api\/v1\/offers\/(\d+)\/answer$/);
      if (request.method !== "POST" || !match) return new Response("not found", { status: 404 });
      calls += 1;
      const id = Number(match[1]);
      const question = url.searchParams.get("question") as Question;
      const model = url.searchParams.get("model") ?? "";
      const heavy = opts.contentHeavy === true;
      if (opts.budgetAfter !== undefined && calls > opts.budgetAfter) {
        return Response.json({ detail: "today's llm.budget is spent" }, { status: 429 });
      }
      if (opts.failAfter !== undefined && calls > opts.failAfter) {
        return Response.json({ detail: "the model runtime did not answer" }, { status: 500 });
      }
      if (opts.noStored && opts.noStored.id === id && opts.noStored.question === question) {
        return Response.json({ detail: `offer ${id} has no stored answer to ${question}` }, { status: 409 });
      }
      if (!["strong", "weak", "silent"].includes(model)) return new Response("unknown model", { status: 400 });
      // A transport failure after the budget was taken: the server answers 200 with no answer at all,
      // after a long wait that must not reach the latency.
      const silent = opts.noAnswer?.id === id && opts.noAnswer.question === question;
      const garbled =
        model === "silent" || (opts.unreadable?.id === id && opts.unreadable.question === question);
      const answer = silent
        ? null
        : garbled
          ? empty(question, id, heavy)
          : model === "weak" && id % 2 === 1
            ? wrong(question, id)
            : stored(question, id, heavy);
      const raw = garbled ? "I cannot tell from this advert." : "{}";
      // strong: 100..119 ms, weak: 200..219 ms, by the advert's position in the sample.
      const millis = silent ? 900_000 : (model === "weak" ? 200 : 100) + (id - 101);
      return Response.json({ question, model, answer, stored: stored(question, id, heavy), raw, millis });
    },
  });
  return { url: `http://localhost:${server.port}`, calls: () => calls, stop: () => server.stop(true) };
}

let server: Stub | undefined;
afterEach(() => server?.stop());

const options = (api: string, ids = IDS, models = ["strong", "weak"]) => ({
  api,
  ids,
  models,
  questions: ["blocks", "fields", "judge"] as Question[],
  floor: 0.8,
});

/**
 * The blocks agreement restated from its definition over the fixture: per kind, the share of that
 * kind's stored blocks labelled the same, averaged over the kinds present.
 */
function macroRecall(labelled: (id: number, index: number) => boolean, ids = IDS, perAdvert = 4): number {
  const total = new Map<string, number>();
  const hits = new Map<string, number>();
  for (const id of ids) {
    for (let index = 0; index < perAdvert; index++) {
      const kind = kindOf(id, index);
      total.set(kind, (total.get(kind) ?? 0) + 1);
      if (labelled(id, index)) hits.set(kind, (hits.get(kind) ?? 0) + 1);
    }
  }
  const recalls = [...total].map(([kind, n]) => (hits.get(kind) ?? 0) / n);
  return recalls.reduce((a, b) => a + b, 0) / recalls.length;
}

describe("measure_routing", () => {
  test("prints one table per question with agreement, p50 and the weak candidate refused", async () => {
    server = stub();
    const report = await run(options(server.url));

    expect(report.stoppedOnBudget).toBe(false);
    expect(server.calls()).toBe(20 * 2 * 3);
    for (const question of ["blocks", "fields", "judge"] as Question[]) {
      const [strong, weak] = report.tables[question];
      expect(strong.candidate).toBe("strong");
      expect(strong.n).toBe(20);
      expect(strong.agreement).toBe(1);
      expect(strong.verdict).toBe("ok");
      expect(strong.p50).toBe(109);
      expect(strong.p95).toBe(118);
      expect(weak.verdict).toBe("refused");
      expect(weak.p50).toBe(209);
    }
    // Blocks: recall per kind, averaged. The weak candidate is right on even adverts only, and the
    // kinds do not spread evenly over odd and even adverts, so this is not 0.5 any more.
    const blocks = report.tables.blocks[1];
    expect(blocks.parts.blocks).toBe(80);
    expect(blocks.agreement).toBeCloseTo(macroRecall((id) => id % 2 === 0), 12);
    for (const kind of KINDS) expect(blocks.parts[kind]).toBeGreaterThan(0);
    // Fields: the stated shares. Start is stated on the 13 adverts not divisible by 3, and the weak
    // candidate keeps it on the 6 even ones; duration is stated everywhere, kept on the 10 even
    // ones; deadline is never stated and is left out. Silent: 7 null starts (4 kept) and 20 null
    // deadlines (10 kept).
    const fields = report.tables.fields[1];
    expect(fields.parts.start).toBeCloseTo(6 / 13, 12);
    expect(fields.parts.duration).toBe(0.5);
    expect(Number.isNaN(fields.parts.deadline)).toBe(true);
    expect(fields.parts.silent).toBeCloseTo(14 / 27, 12);
    expect(fields.agreement).toBeCloseTo((6 / 13 + 0.5) / 2, 12);
    // Judge: only adverts where either side gives points. Factors 0 and 2 have five even adverts at
    // 0 on both sides, dropped: 5 of 15. Factors 1 and 3 never sit at 0 on even adverts: 10 of 20.
    const judge = report.tables.judge[1];
    expect(judge.parts).toMatchObject({ stack_mismatch_dominant: 0.5, vague_description: 0.5, mad: 0.5 });
    expect(judge.parts.role_fit).toBeCloseTo(1 / 3, 12);
    expect(judge.agreement).toBeCloseTo(5 / 12, 12);
    expect(report.tables.judge[0].parts.mad).toBe(0);

    const text = render(report);
    expect(text).toContain("blocks");
    expect(text).toContain("fields");
    expect(text).toContain("judge");
    expect(text.match(/p50 ms/g)?.length).toBe(3);
    // Three weak rows and three empty-answer rows.
    expect(text.match(/refused/g)?.length).toBe(6);
    expect(text).not.toContain("budget");
  });

  test("the empty answer is printed first in each table and lands near 0", async () => {
    server = stub();
    const report = await run(options(server.url));

    const blocks = report.baselines.blocks!;
    const fields = report.baselines.fields!;
    const judge = report.baselines.judge!;
    // Blocks: CONTENT's recall is 1 and every other kind's is 0, so 1 over the six kinds present.
    expect(blocks.agreement).toBeCloseTo(1 / 6, 12);
    expect(blocks.parts.CONTENT).toBe(1);
    expect(blocks.parts.CHROME).toBe(0);
    expect(fields.agreement).toBe(0);
    expect(fields.parts.silent).toBe(1);
    expect(judge.agreement).toBe(0);
    for (const row of [blocks, fields, judge]) {
      expect(row.candidate).toBe("empty answer");
      expect(row.n).toBe(20);
      expect(row.agreement).toBeLessThan(0.2);
      expect(row.verdict).toBe("refused");
    }

    const lines = render(report).split("\n");
    for (const question of ["blocks", "fields", "judge"]) {
      const title = lines.findIndex((l) => l.startsWith(`${question} (floor`));
      expect(lines[title + 2]).toMatch(/^-+/);
      expect(lines[title + 3]).toMatch(/^empty answer /);
      expect(lines[title + 4]).toMatch(/^strong /);
    }
  });

  test("a candidate that always answers the empty shape is refused though most blocks are CONTENT", async () => {
    server = stub({ contentHeavy: true });
    const report = await run({ ...options(server.url, IDS, ["strong", "silent"]), questions: ["blocks"] });

    const [strong, silent] = report.tables.blocks;
    // 180 of the 200 stored blocks are CONTENT: a plain share of equal labels would give it 90 %.
    expect(silent.parts.blocks).toBe(200);
    expect(silent.parts.CONTENT).toBe(1);
    expect(silent.parts.CHROME).toBe(0);
    expect(silent.agreement).toBe(0.5);
    expect(silent.verdict).toBe("refused");
    expect(silent.parts.unreadable).toBe(20);
    expect(strong.agreement).toBe(1);
    expect(strong.parts.unreadable).toBe(0);
    expect(report.baselines.blocks!.agreement).toBe(0.5);
    expect(render(report)).toContain("unreadable");
  });

  test("a reply that could not be read is counted as unreadable, apart from no answer", async () => {
    server = stub({ unreadable: { id: 104, question: "fields" } });
    const report = await run(options(server.url));

    const [strong] = report.tables.fields;
    expect(strong.parts.unreadable).toBe(1);
    expect(strong.parts["no answer"]).toBe(0);
    // Advert 104 states start and duration; the empty shape misses both.
    expect(strong.parts.start).toBeCloseTo(12 / 13, 12);
    expect(strong.parts.duration).toBe(19 / 20);
    expect(report.tables.blocks[0].parts.unreadable).toBe(0);
  });

  test("refuses a sample under 20 adverts, before any call", async () => {
    server = stub();
    await expect(run(options(server.url, IDS.slice(0, 5)))).rejects.toThrow(
      "5 adverts is too few to measure agreement: give at least 20.",
    );
    expect(server.calls()).toBe(0);
  });

  test("the command line exits non-zero on 5 adverts and says why", () => {
    const cli = Bun.spawnSync(
      ["bun", join(import.meta.dir, "measure_routing.ts"), "--models=strong", "1", "2", "3", "4", "5"],
      { env: { ...process.env, LEADGEN_API: "http://127.0.0.1:9" } },
    );
    expect(cli.exitCode).not.toBe(0);
    expect(cli.stderr.toString()).toContain("give at least 20");
  });

  test("a spent budget stops the run at once, the report says so, and the short row is too few", async () => {
    server = stub({ budgetAfter: 6 });
    const report = await run(options(server.url));

    expect(server.calls()).toBe(7);
    expect(report.stoppedOnBudget).toBe(true);
    const [strong] = report.tables.blocks;
    expect(strong.n).toBe(6);
    expect(strong.agreement).toBe(1);
    expect(strong.verdict).toBe("too few");
    expect(report.baselines.blocks!.n).toBe(6);
    expect(report.baselines.blocks!.verdict).toBe("too few");
    const text = render(report);
    expect(text).toContain("too few");
    expect(text).toContain("Stopped on the budget: today's llm.budget is spent");
  });

  test("an advert with no stored answer is skipped and counted, and 19 compared is too few", async () => {
    server = stub({ noStored: { id: 105, question: "fields" } });
    const report = await run(options(server.url));

    const [strong, weak] = report.tables.fields;
    expect(strong.n).toBe(19);
    expect(strong.skipped).toBe(1);
    expect(strong.verdict).toBe("too few");
    expect(weak.skipped).toBe(1);
    expect(report.tables.blocks[0].skipped).toBe(0);
    expect(report.tables.blocks[0].verdict).toBe("ok");
    expect(render(report)).toMatch(/skipped/);
  });

  test("an answer that never arrived counts against the candidate, and its wait not in the latency", async () => {
    server = stub({ noAnswer: { id: 102, question: "blocks" } });
    const report = await run(options(server.url));

    const [strong] = report.tables.blocks;
    expect(strong.n).toBe(20);
    expect(strong.parts["no answer"]).toBe(1);
    expect(strong.parts.unreadable).toBe(0);
    // Advert 102's blocks read as CONTENT: its CONTENT block still counts, the other three miss.
    expect(strong.agreement).toBeCloseTo(macroRecall((id, index) => id !== 102 || kindOf(id, index) === "CONTENT"), 12);
    expect(strong.agreement).toBeLessThan(1);
    // 19 answered calls, 100..119 ms without 101: the 900 000 ms call is left out of both.
    expect(strong.p50).toBe(110);
    expect(strong.p95).toBe(119);
    expect(report.tables.fields[0].parts["no answer"]).toBe(0);
    expect(render(report)).toContain("no answer");
  });

  test("an error after the first call prints what was measured, then the sentence, and exits 1", async () => {
    server = stub({ failAfter: 6 });
    const report = await run(options(server.url));

    expect(server.calls()).toBe(7);
    expect(report.stoppedOnBudget).toBe(false);
    expect(report.stoppedOnError).toBe(true);
    expect(report.tables.blocks[0].n).toBe(6);
    const text = render(report);
    expect(text).toContain("p50 ms");
    expect(text).toContain("Stopped on an error: offer 107, blocks, strong: HTTP 500 the model runtime did not answer");

    server.stop();
    server = stub({ failAfter: 6 });
    const cli = Bun.spawn(
      ["bun", join(import.meta.dir, "measure_routing.ts"), "--models=strong,weak", ...IDS.map(String)],
      { env: { ...process.env, LEADGEN_API: server.url }, stdout: "pipe", stderr: "pipe" },
    );
    expect(await cli.exited).toBe(1);
    expect(await new Response(cli.stdout).text()).toContain("p50 ms");
    expect(await new Response(cli.stderr).text()).toContain("HTTP 500 the model runtime did not answer");
  });
});
