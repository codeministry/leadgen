/**
 * Retakes the screenshots the root README and the guides render, from the English demo stack.
 *
 *   bun run docs:shots                                   # every shot, from :14201
 *   DOCS_SHOTS_URL=http://… bun run docs:shots
 *   DOCS_SHOTS_ONLY=dashboard-light,rules-dark bun run docs:shots   # just these
 *
 * Writes `docs/screenshots/<id>.png` at 1440×900, the size every image there has. The theme is
 * part of the id and chosen per screen, not per run: the README picks whichever of the two shows a
 * screen best (docs/README.md).
 *
 * **Only ever against the demo stack, and the English one.** A screenshot is published with the
 * repository, so this reads the instance's configured sources first and stops unless they are
 * exactly the demo's. English because the README is English: German adverts under English labels
 * read as a broken translation, which is why the help shots take English from :14201 too. Start it
 * as `capture-help-shots.ts` describes.
 */
import {resolve} from 'node:path';
import {chromium, type Page} from 'playwright';

const BASE = process.env['DOCS_SHOTS_URL'] ?? 'http://127.0.0.1:14201';
const OUT = resolve(import.meta.dir, '../../docs/screenshots');
const VIEWPORT = {width: 1440, height: 900};
const ONLY = (process.env['DOCS_SHOTS_ONLY'] ?? '').split(',').map((id) => id.trim()).filter((id) => id !== '');

/** What the demo's `sources.yaml` declares, and all it declares. */
const DEMO_SOURCES = ['demo-newsletter', 'manual-inbox'];

interface Shot {
    readonly theme: 'light' | 'dark';
    /** The path to open, given the offer the shortlist puts first. */
    readonly route: (top: number) => string;
    /** 'load' where the screen keeps a request open on purpose; networkidle otherwise. */
    readonly waitUntil?: 'load' | 'networkidle';
    /** What has to be on screen before the picture is taken, beyond the route. */
    readonly prepare?: (page: Page) => Promise<void>;
}

/**
 * Scrolls the element to the top of whatever pane scrolls it, clear of the sticky header: without
 * the margin its own heading lands under the header and the picture starts mid-sentence.
 */
async function scrollTo(page: Page, selector: string): Promise<void> {
    const target = page.locator(selector).first();
    await target.waitFor();
    await target.evaluate((element) => {
        (element as HTMLElement).style.scrollMarginTop = '96px';
        element.scrollIntoView({block: 'start'});
    });
}

const SHOTS: Record<string, Shot> = {
    'dashboard-light': {theme: 'light', route: () => '/dashboard'},
    'shortlist-light': {theme: 'light', route: (top) => `/shortlist/${top}`},
    // The same screen further down its reading column: the status control and the package.
    'offer-detail-light': {
        theme: 'light',
        route: (top) => `/shortlist/${top}`,
        prepare: (page) => scrollTo(page, 'section:has(> lg-application-panel)'),
    },
    'pipeline-light': {theme: 'light', route: () => '/pipeline'},
    'analytics-light': {theme: 'light', route: () => '/analytics'},
    'sources-dark': {theme: 'dark', route: () => '/sources/demo-newsletter'},
    // A reader's own path: one step out while the sheet is closed, then the stage. At zoom 1 beside
    // the sheet the graph shows three columns cut at the left edge; zoomed out first, the canvas
    // pans the picked stage clear of the sheet itself, as it does for anyone.
    'rules-dark': {
        theme: 'dark',
        route: () => '/workflow',
        prepare: async (page) => {
            await page.locator('[data-action="zoom-out"]').first().click();
            await page.waitForTimeout(400);
            await page.locator('lg-flow-node a[data-stage="SCORE"]').first().click();
            await page.locator('lg-stage-sheet').first().waitFor();
            await page.waitForTimeout(800);
        },
    },
    // The chat docked beside the offer the shortlist puts first, with one real answer in it: the
    // question goes through the composer and the demo's own model answers, so the picture shows
    // what the chat actually does with this corpus. A local model can take minutes to answer.
    'chat-light': {
        theme: 'light',
        route: (top) => `/shortlist/${top}?chat=new`,
        // The empty chat asks the model to phrase its suggestions, so the network never idles.
        waitUntil: 'load',
        prepare: async (page) => {
            // Earlier takes left their conversation behind, and the rail showed one row per take.
            const question = 'Which three offers on my shortlist score highest, and what do they ask for?';
            const earlier = (await (await page.request.get(`${BASE}/api/v1/chat/conversations`)).json()) as {id: number}[];
            if (earlier.length > 0) {
                await page.request.post(`${BASE}/api/v1/chat/conversations/bulk-delete`, {data: {ids: earlier.map((c) => c.id)}});
                await page.reload({waitUntil: 'load'});
            }
            const composer = page.locator('.lg-chat-composer textarea').first();
            await composer.waitFor();
            await composer.fill(question);
            await page.locator('.lg-chat-send').first().click();
            await page.locator('.lg-chat-thread[aria-busy="true"]').first()
                .waitFor({timeout: 30_000})
                .catch(() => undefined);
            await page.waitForFunction(
                () => document.querySelector('.lg-chat-thread[aria-busy="true"]') === null,
                undefined,
                {timeout: 600_000},
            );
        },
    },
    // The help drawer at its how-it-works chapter, over the screen it was opened from.
    'help-light': {
        theme: 'light',
        route: () => '/workflow',
        prepare: async (page) => {
            await page.locator('.help-button').first().click();
            const chapter = page.locator('[data-chapter="how-it-works"]').first();
            if (!(await chapter.isVisible())) {
                await page.getByRole('button', {name: /all chapters/i}).first().click();
            }
            await chapter.click();
        },
    },
};

/** Stops unless the instance is the demo: its sources, and nothing else. */
async function refuseAnythingButTheDemo(page: Page): Promise<void> {
    const response = await page.request.get(`${BASE}/api/v1/sources`);
    const body = (await response.json()) as {id: string}[] | {sources: {id: string}[]};
    const ids = (Array.isArray(body) ? body : body.sources).map((source) => source.id).sort();
    if (ids.join(',') !== [...DEMO_SOURCES].sort().join(',')) {
        throw new Error(`${BASE} is not the demo stack: it declares ${ids.join(', ')}`);
    }
}

/** The offer the shortlist puts first, which the two offer shots open. */
async function topOffer(page: Page): Promise<number> {
    const response = await page.request.get(`${BASE}/api/v1/offers?limit=1`);
    const body = (await response.json()) as {entries: {offer: {id: number}}[]};
    const first = body.entries[0];
    if (first === undefined) throw new Error('the demo shortlist is empty; run an ingest first');
    return first.offer.id;
}

async function main(): Promise<void> {
    const browser = await chromium.launch();
    try {
        const probe = await browser.newPage();
        await refuseAnythingButTheDemo(probe);
        const top = await topOffer(probe);
        await probe.close();

        for (const [id, shot] of Object.entries(SHOTS)) {
            if (ONLY.length > 0 && !ONLY.includes(id)) continue;
            const context = await browser.newContext({viewport: VIEWPORT, deviceScaleFactor: 1, reducedMotion: 'reduce'});
            await context.addInitScript((mode) => {
                localStorage.setItem('lg-language', 'en');
                localStorage.setItem('lg-theme', mode);
            }, shot.theme);
            const page = await context.newPage();
            await page.goto(`${BASE}${shot.route(top)}`, {waitUntil: shot.waitUntil ?? 'networkidle'});
            await shot.prepare?.(page);
            // Fonts, and the last layout pass after the data arrived.
            await page.evaluate(() => document.fonts.ready);
            await page.waitForTimeout(600);
            const file = resolve(OUT, `${id}.png`);
            await page.screenshot({path: file, animations: 'disabled'});
            console.log(`wrote ${file}`);
            await context.close();
        }
    } finally {
        await browser.close();
    }
}

await main();
