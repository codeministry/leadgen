/**
 * Retakes every help screenshot from the demo stack, in both languages and both themes.
 *
 *   bun run help:shots        # German shots from :14200, English shots from :14201
 *   HELP_SHOTS_URL_DE=http://… HELP_SHOTS_URL_EN=http://… bun run help:shots
 *
 * Writes `public/help/shots/<lang>/<id>-<light|dark>.webp` for every entry of `SHOTS` below,
 * and nothing else: a file in that folder without an entry fails `help-shots.spec.ts`.
 *
 * **Only ever against the demo stack.** A screenshot is published with the repository, and the
 * adverts a real instance holds are not ours to publish. So the script asks the instance which
 * sources it reads and stops unless they are exactly the demo's. Start the demo under its own
 * compose project, so its database is not the real one:
 *
 *   POSTGRES_PORT=15433 SERVER_PORT=18080 WEB_PORT=14200 \
 *     docker compose -p leadgen-demo -f docker-compose.yml -f docker-compose.demo.yml up --build -d
 *
 * and the English one beside it, whose adverts are the same corpus in English
 * (`bun demo/generate-corpus.ts --lang en --until 2026-09-02` first):
 *
 *   POSTGRES_PORT=15434 SERVER_PORT=18081 WEB_PORT=14201 docker compose -p leadgen-demo-en \
 *     -f docker-compose.yml -f docker-compose.demo.yml -f docker-compose.demo-en.yml up --build -d
 *
 * An English help showing German adverts under English labels reads as a broken translation,
 * which is why each language is taken from its own instance. Press Run ingest once on each with
 * a local model configured, so the offers carry scores, and record a few applications (at
 * least one sent, with its package) so the pipeline and the application shots have something
 * to show.
 *
 * Every shot is a fixed rectangle, not an element's box: German runs longer than English, and a
 * box that grows with the text would give the four variants four sizes, while the drawer
 * reserves one (`HELP_SHOTS` in `help-chapters.ts`). The rectangles start below the header,
 * because the header can name a configured model, and the script refuses a shot whose frame
 * prints one of the instance's model names anywhere. The capture is at twice the CSS size and
 * downscaled to `OUTPUT_WIDTH`, which is about the drawer's width on a 2x screen.
 *
 * Needs `cwebp` (libwebp) on PATH, and the Chromium Playwright installs
 * (`bunx playwright install chromium` once).
 */
import {mkdirSync, mkdtempSync, readdirSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {chromium, type Locator, type Page} from 'playwright';
import {HELP_SHOTS} from '../src/app/core/help/help-chapters';

const LANGUAGES = ['en', 'de'] as const;

/** One demo instance per language, because the adverts are content in that language. */
const BASES: Record<(typeof LANGUAGES)[number], string> = {
    de: process.env['HELP_SHOTS_URL_DE'] ?? 'http://127.0.0.1:14200',
    en: process.env['HELP_SHOTS_URL_EN'] ?? 'http://127.0.0.1:14201',
};
const OUT = resolve(import.meta.dir, '../public/help/shots');
const THEMES = ['light', 'dark'] as const;

/** What the demo's `sources.yaml` declares, and all it declares. */
const DEMO_SOURCES = ['demo-newsletter', 'manual-inbox'];

const VIEWPORT = {width: 1280, height: 800};
const OUTPUT_WIDTH = 1400;
const QUALITY = 78;

/** The header's height at the app's 93.75 % root size, which every rectangle starts below. */
const BELOW_HEADER = 53;

interface Rect {
    readonly x: number;
    readonly y: number;
    readonly width: number;
    readonly height: number;
}

interface Shot {
    /** The path to open on the instance at `base`, before the language and theme are applied. */
    readonly route: (base: string, page: Page) => Promise<string>;
    /** What has to be on screen before the picture is taken, beyond the route. */
    readonly prepare?: (page: Page) => Promise<void>;
    /**
     * A fixed rectangle of the window; or, with `anchor`, a fixed size whose top left corner
     * sits `PAD` above and left of the anchor, scrolled into view and kept inside the window.
     */
    readonly rect: Rect;
    readonly anchor?: (page: Page) => Locator;
    /**
     * Elements left out of the picture, hidden rather than cropped away: a line that prints a
     * configured value in the middle of what the shot is about.
     */
    readonly hide?: readonly string[];
}

const PAD = 12;

/** The rectangle a shot is taken from: its own, or its size placed at its anchor. */
async function frame(page: Page, shot: Shot): Promise<Rect> {
    if (shot.anchor === undefined) return shot.rect;
    const target = shot.anchor(page).first();
    // Centred rather than merely visible, so the rectangle below it is not clamped to the edge.
    await target.evaluate((element) => element.scrollIntoView({block: 'center'}));
    const box = await target.boundingBox();
    if (box === null) throw new Error('the anchor is not on screen');
    const {width, height} = shot.rect;
    const x = Math.max(0, Math.min(Math.round(box.x) - PAD, VIEWPORT.width - width));
    const y = Math.max(BELOW_HEADER, Math.min(Math.round(box.y) - PAD, VIEWPORT.height - height));
    return {x, y, width, height};
}

/** An anchored shot's size; where it sits is decided on the page. */
function sized(width: number, height: number): Rect {
    return {x: 0, y: 0, width, height};
}

/** Opens a popover or dialog by its trigger and waits until it is open. */
async function open(page: Page, trigger: string, opened: string): Promise<void> {
    await page.locator(trigger).first().click();
    await page.locator(opened).first().waitFor();
}

/**
 * The offer the detail shots show: the one whose application was sent last, so the detail
 * carries a score, a letter as it went out and a package. The English instance's applications
 * mirror the German ones, so both languages show the same offer.
 */
async function featuredOffer(base: string, page: Page): Promise<number> {
    const response = await page.request.get(`${base}/api/v1/applications`);
    const applications = (await response.json()) as {offerId: number; status: string; sentOn: string | null}[];
    const sent = applications
        .filter((application) => application.status === 'SENT' && application.sentOn !== null)
        .sort((a, b) => b.sentOn!.localeCompare(a.sentOn!) || a.offerId - b.offerId);
    if (sent.length === 0) throw new Error(`${base} has no sent application to show; record one first.`);
    return sent[0]!.offerId;
}

const CONTENT: Rect = {x: 0, y: BELOW_HEADER, width: 1280, height: 720};

/**
 * The manifest. The key is the shot's id, which the chapters place as
 * `<!-- screenshot: id -->` and which `HELP_SHOTS` lists with its output size.
 */
const SHOTS: Record<string, Shot> = {
    'run-phases-rail': {
        route: async () => '/rules',
        prepare: async (page) => {
            await page.locator('lg-stage-rail').waitFor();
        },
        rect: {x: 0, y: BELOW_HEADER, width: 1280, height: 620},
    },
    dashboard: {
        route: async () => '/dashboard',
        rect: CONTENT,
    },
    'shortlist-split': {
        route: async (base, page) => `/shortlist/${await featuredOffer(base, page)}`,
        prepare: async (page) => {
            await page.locator('lg-offer-detail h2').first().waitFor();
        },
        rect: CONTENT,
    },
    'pipeline-board': {
        route: async () => '/pipeline',
        rect: CONTENT,
    },
    'analytics-overview': {
        route: async () => '/analytics',
        rect: CONTENT,
    },
    'rules-stage': {
        route: async () => '/rules?stage=filter',
        prepare: async (page) => {
            await page.locator('lg-stage-rail').waitFor();
        },
        rect: CONTENT,
    },
    'sources-panel': {
        route: async () => '/sources/demo-newsletter',
        rect: CONTENT,
    },
    'offer-why-scored': {
        route: async (base, page) => `/shortlist/${await featuredOffer(base, page)}`,
        anchor: (page) => page.locator('lg-offer-detail section.lg-panel:has(.reason)'),
        // The line beside the score ring names the ruleset and the model that judged.
        hide: ['lg-offer-detail .score-row > p'],
        rect: sized(660, 560),
    },
    'offer-ask': {
        route: async (base, page) => `/shortlist/${await featuredOffer(base, page)}`,
        anchor: (page) => page.locator('lg-offer-detail section.lg-panel:has(lg-ask-panel)'),
        rect: sized(660, 380),
    },
    'application-panel': {
        route: async (base, page) => `/shortlist/${await featuredOffer(base, page)}`,
        anchor: (page) => page.locator('lg-offer-detail section.lg-panel:has(lg-application-panel)'),
        rect: sized(660, 520),
    },
    'cover-letter': {
        route: async (base, page) => `/shortlist/${await featuredOffer(base, page)}`,
        anchor: (page) => page.locator('lg-offer-detail section.lg-panel:has(lg-cover-letter)'),
        rect: sized(660, 520),
    },
    'filters-panel': {
        route: async () => '/shortlist?deadlineOpen=1&portal=portal-a',
        prepare: (page) => open(page, 'lg-facet-panel .facet-button', 'lg-facet-panel [popover]:popover-open'),
        anchor: (page) => page.locator('.filters'),
        rect: sized(620, 560),
    },
    'sort-menu': {
        route: async () => '/shortlist?sort=deadline',
        prepare: (page) => open(page, 'lg-sort-menu .sort-button', 'lg-sort-menu .sort-panel:popover-open'),
        anchor: (page) => page.locator('.sort-controls'),
        rect: sized(520, 420),
    },
    'run-confirm': {
        route: async () => '/dashboard',
        // Opens the confirmation and nothing more: the run itself starts on its confirm button,
        // which is never pressed here.
        prepare: (page) => open(page, '.ingest-button', '.lg-run-confirm[open] .modal-box'),
        anchor: (page) => page.locator('.lg-run-confirm .modal-box'),
        rect: sized(560, 280),
    },
    'settings-panel': {
        route: async () => '/dashboard',
        prepare: (page) => open(page, '.settings-button', '#app-settings:popover-open'),
        anchor: (page) => page.locator('#app-settings'),
        // The version line names the product and its release, which a help text never does.
        hide: ['#app-settings .api-version'],
        rect: sized(360, 320),
    },
};

/** The size a rectangle comes out at, which `HELP_SHOTS` has to state. */
function outputSize(rect: Rect): {width: number; height: number} {
    const width = Math.min(OUTPUT_WIDTH, rect.width * 2);
    return {width, height: Math.round((rect.height * width) / rect.width)};
}

async function refuseAnythingButTheDemo(base: string, page: Page): Promise<void> {
    const response = await page.request.get(`${base}/api/v1/sources`);
    if (!response.ok()) {
        throw new Error(`${base} did not answer /api/v1/sources (${response.status()}); is the demo stack up?`);
    }
    const body = (await response.json()) as {sources: {id: string}[]};
    const ids = body.sources.map((source) => source.id).sort();
    if (JSON.stringify(ids) !== JSON.stringify([...DEMO_SOURCES].sort())) {
        throw new Error(`${base} reads ${ids.join(', ')}, not the demo's sources. Refusing to take screenshots of it.`);
    }
}

/**
 * The model names the instance is configured with. They are values of the operator's `.env`, and
 * the header, the rules screen's AI steps and an offer's score line all print them; a shot that
 * frames one of them publishes it.
 */
async function modelNames(base: string, page: Page): Promise<string[]> {
    const response = await page.request.get(`${base}/api/v1/scoring-models`);
    const body = (await response.json()) as {available: string[]};
    return body.available;
}

/** Fails when any of `names` is printed inside `rect`: the manifest has to frame the shot elsewhere. */
async function refuseAConfiguredValueInFrame(page: Page, id: string, rect: Rect, names: string[]): Promise<void> {
    const hits = await page.evaluate(
        ([{x, y, width, height}, values]) => {
            const found: string[] = [];
            const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
            for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
                const text = node.textContent ?? '';
                const value = values.find((name) => text.includes(name));
                if (value === undefined) continue;
                if (node.parentElement && getComputedStyle(node.parentElement).visibility === 'hidden') continue;
                const range = document.createRange();
                range.selectNodeContents(node);
                for (const box of range.getClientRects()) {
                    const inside = box.right > x && box.left < x + width && box.bottom > y && box.top < y + height;
                    if (inside && box.width > 0) found.push(value);
                }
            }
            return found;
        },
        [rect, names] as const,
    );
    if (hits.length > 0) {
        throw new Error(`'${id}' frames the configured model name ${hits[0]}; frame it elsewhere.`);
    }
}

/** Every entry has a registered size, and every registered size an entry, matching the rectangle. */
function checkRegistry(): void {
    const problems: string[] = [];
    for (const [id, shot] of Object.entries(SHOTS)) {
        const expected = outputSize(shot.rect);
        const registered = (HELP_SHOTS as Record<string, {width: number; height: number}>)[id];
        if (registered?.width !== expected.width || registered?.height !== expected.height) {
            problems.push(`  '${id}': {width: ${expected.width}, height: ${expected.height}},`);
        }
    }
    for (const id of Object.keys(HELP_SHOTS)) {
        if (!(id in SHOTS)) problems.push(`  '${id}' is registered but has no entry here`);
    }
    if (problems.length > 0) {
        throw new Error(`HELP_SHOTS in help-chapters.ts does not match the manifest:\n${problems.join('\n')}`);
    }
}

async function main(): Promise<void> {
    checkRegistry();
    const browser = await chromium.launch();
    const scratch = mkdtempSync(join(tmpdir(), 'help-shots-'));
    try {
        // Both instances are checked before a single file is replaced.
        const models: string[] = [];
        const probe = await browser.newPage();
        for (const language of LANGUAGES) {
            await refuseAnythingButTheDemo(BASES[language], probe);
            models.push(...(await modelNames(BASES[language], probe)));
        }
        await probe.close();

        for (const language of LANGUAGES) {
            const base = BASES[language];
            const dir = resolve(OUT, language);
            rmSync(dir, {recursive: true, force: true});
            mkdirSync(dir, {recursive: true});
            for (const theme of THEMES) {
                const context = await browser.newContext({
                    viewport: VIEWPORT,
                    deviceScaleFactor: 2,
                    reducedMotion: 'reduce',
                });
                await context.addInitScript(
                    ([lang, mode]) => {
                        localStorage.setItem('lg-language', lang);
                        localStorage.setItem('lg-theme', mode);
                    },
                    [language, theme] as const,
                );
                const page = await context.newPage();
                for (const [id, shot] of Object.entries(SHOTS)) {
                    await page.goto(`${base}${await shot.route(base, page)}`, {waitUntil: 'networkidle'});
                    await shot.prepare?.(page);
                    for (const selector of shot.hide ?? []) {
                        await page.addStyleTag({content: `${selector} { visibility: hidden !important; }`});
                    }
                    // Fonts and the last layout pass after data arrived.
                    await page.evaluate(() => document.fonts.ready);
                    await page.waitForTimeout(400);
                    const rect = await frame(page, shot);
                    await refuseAConfiguredValueInFrame(page, id, rect, models);
                    const png = join(scratch, `${id}.png`);
                    await page.screenshot({path: png, clip: rect, animations: 'disabled'});
                    const {width, height} = outputSize(shot.rect);
                    const webp = resolve(dir, `${id}-${theme}.webp`);
                    const result = Bun.spawnSync([
                        'cwebp', '-quiet', '-q', String(QUALITY), '-m', '6', '-resize', String(width), String(height),
                        png, '-o', webp,
                    ]);
                    if (result.exitCode !== 0) {
                        throw new Error(`cwebp failed on ${id}: ${result.stderr.toString()}`);
                    }
                    console.log(`${language}/${id}-${theme}.webp`);
                }
                await context.close();
            }
            console.log(`${language}: ${readdirSync(dir).length} files`);
        }
    } finally {
        rmSync(scratch, {recursive: true, force: true});
        await browser.close();
    }
}

await main();
