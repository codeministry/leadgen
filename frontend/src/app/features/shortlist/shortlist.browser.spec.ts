import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting, TestRequest} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router, Routes, withComponentInputBinding} from '@angular/router';
import {page} from 'vitest/browser';
import {ApplicationView, PipelineLane} from '@core/model/application';
import {PendingDocument} from '@core/model/manual-document';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {ShortlistPage as ShortlistPayload} from '@core/model/shortlist-page';
import {TRANSITIONS} from '@core/model/transitions.fixture';
import {provideScoreThresholds} from '@core/store/score-thresholds.provider';
import {App} from '../../app';
import {routes} from '../../app.routes';

/*
 * Titles the market actually writes: long, with compound words and a pipe, because a short
 * title fits a sliver and the defect this spec is about is exactly a column cut to a sliver.
 */
const TITLE = 'SAP SAC Analytics – Lead Integration & Governance | Remote-Projekt für Spring Boot und Kafka';

function entry(id: number): ShortlistEntry {
    return {
        offer: {
            id,
            sourceName: 'sample-newsletter',
            externalId: `https://example.invalid/${id}`,
            title: `${TITLE} ${id}`,
            description: 'Ablösung eines Monolithen durch eine ereignisgetriebene Architektur mit Kafka.',
            url: `https://example.invalid/${id}`,
            location: 'Köln',
            portal: 'portal-a',
            agency: 'Agentur Beispiel GmbH',
            publishedOn: '2026-09-01',
            tags: ['Java', 'Spring Boot', 'Kafka', 'Kubernetes'],
            rateEur: 95,
            remotePercent: 80,
            startsOn: '2026-10-01',
            startText: null,
            durationMonths: 6,
            applyBy: null,
            applyByText: null,
            duration: null,
            workload: null,
            language: 'de',
            fullText: 'Wir suchen einen erfahrenen Entwickler für die Ablösung eines Monolithen.',
            packageDir: null,
            ingestedAt: '2026-09-02T05:12:00Z',
            archivedAt: null,
            archiveSource: null,
            enrichmentNote: null,
        },
        score: {value: 88, hardPass: true, reasons: [], model: null, rulesetVersion: '1'},
        flags: {incomplete: false, remoteUnknown: false, possibleDuplicate: false},
        sources: [{portal: 'portal-a', agency: null, url: `https://example.invalid/${id}`}],
        content: [],
    };
}

const ENTRIES: readonly ShortlistEntry[] = [entry(1), entry(2), entry(3)];

const OFFERS: ShortlistPayload = {
    entries: ENTRIES,
    nextCursor: null,
    matched: ENTRIES.length,
    unscored: 0,
    total: ENTRIES.length,
    portals: ['portal-a'],
    related: null,
    relatedTo: null,
};

const LANES: readonly PipelineLane[] = [
    {id: 'open', label: 'Open', states: ['NEW', 'SHORTLISTED', 'PACKAGED']},
    {id: 'out', label: 'Out', states: ['SENT', 'REPLIED']},
];

const APPLICATION: ApplicationView = {
    id: 4,
    offerId: 1,
    status: 'NEW',
    title: `${TITLE} 1`,
    agency: null,
    portal: 'portal-a',
    url: 'https://example.invalid/1',
    scoreValue: 82,
    rateEur: null,
    packageDir: null,
    sentOn: null,
    followUpOn: null,
    followUpDue: false,
    outcome: null,
    note: null,
    updatedAt: '2026-09-01T08:00:00Z',
};

const DOCUMENT = 'senior-java.md';

const PENDING: PendingDocument = {
    name: DOCUMENT,
    size: 412,
    uploadedAt: '2026-09-01T08:00:00Z',
    text: `---\ntitle: ${TITLE}\n---\n\nAblösung eines Monolithen.`,
    offer: {
        externalId: null,
        title: TITLE,
        description: 'Ablösung eines Monolithen.',
        url: null,
        location: 'Köln',
        portal: null,
        agency: null,
        publishedOn: null,
        tags: [],
        fingerprint: null,
    },
    fromModel: [],
    duplicateOfId: null,
    duplicateOfTitle: null,
};

/** The answer to every request a screen or the shell makes; `undefined` for one nobody expected. */
function answer(request: TestRequest): unknown {
    const url = request.request.url;
    if (url === '/api/v1/offers') return OFFERS;
    const offer = /^\/api\/v1\/offers\/(\d+)$/.exec(url);
    if (offer) return entry(Number(offer[1]));
    if (url === '/api/v1/applications') return [APPLICATION];
    if (url === '/api/v1/applications/lanes') return LANES;
    if (url === '/api/v1/applications/transitions') return TRANSITIONS;
    if (url === '/api/v1/sources/manual/pending') return [PENDING];
    if (url === '/api/v1/chat/capability') return {present: true};
    if (url === '/api/v1/chat/conversations') return [];
    return undefined;
}

/*
 * The real route table, plus the review screen: it is parked (no route, no nav entry) but its
 * code, its split and its stylesheet ship, and the claim names it.
 */
const ROUTES: Routes = [
    ...routes.slice(0, -1),
    {path: 'review', loadComponent: () => import('@features/review/review').then((m) => m.Review)},
    ...routes.slice(-1),
];

interface SplitView {
    readonly name: string;
    readonly url: string;
    readonly host: string;
    /** Every column of the split, in the order they sit. */
    readonly columns: readonly string[];
    /** A column that scrolls sideways by design, so its content may be wider than its box. */
    readonly scroller?: string;
}

const VIEWS: readonly SplitView[] = [
    {
        name: 'the shortlist with an offer open',
        url: '/shortlist/1',
        host: 'lg-shortlist-page',
        columns: ['.list-col', '.detail-pane'],
    },
    {
        name: 'the pipeline with a card open',
        url: '/pipeline/1',
        host: 'lg-pipeline',
        columns: ['.board-col', '.detail-pane'],
        scroller: '.board-col',
    },
    {
        name: 'the review queue with a document open',
        url: `/review?doc=${DOCUMENT}`,
        host: 'lg-review',
        columns: ['.queue-col', '.detail-pane'],
    },
];

/**
 * The split views beside the docked chat (ISC-472): the real root, shell, header, panel and
 * the real screens, measured in a real browser because the claim is about boxes.
 *
 * <p>With the chat open the page is the window minus the panel: at 1440 that is 600 px, at 820
 * about 280. The splits used to key on the viewport, so beside the panel they kept two columns
 * that do not fit and cut the reading column to a sliver. Keyed on the page's own box they fall
 * to one column there, and with the chat closed they split exactly where they always did.
 */
describe('the split views beside the chat (ISC-472)', () => {
    let fixture: ComponentFixture<App>;
    let http: HttpTestingController;
    let router: Router;

    beforeEach(async () => {
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                provideRouter(ROUTES, withComponentInputBinding()),
                provideScoreThresholds(),
            ],
        });
        http = TestBed.inject(HttpTestingController);
        router = TestBed.inject(Router);
        fixture = TestBed.createComponent(App);
        document.body.appendChild(fixture.nativeElement);
    });

    afterEach(() => (fixture.nativeElement as HTMLElement).remove());

    /** Change detection, lazy routes and every request answered, until nothing is pending. */
    async function settle(): Promise<void> {
        for (let i = 0; i < 12; i++) {
            await new Promise((resolve) => setTimeout(resolve, 0));
            for (const request of http.match(() => true)) {
                if (request.cancelled) continue;
                const body = answer(request);
                if (body === undefined) {
                    request.flush(null, {status: 404, statusText: 'Not in this spec'});
                } else {
                    request.flush(body);
                }
            }
            fixture.detectChanges();
        }
        await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    }

    /** The slide-in ends; looping marks would never finish, so they are not waited on. */
    async function transitions(): Promise<void> {
        const finite = document
            .getAnimations()
            .filter((animation) => animation.effect?.getComputedTiming().iterations !== Infinity);
        await Promise.all(finite.map((animation) => animation.finished));
    }

    async function open(url: string, width: number, chat: boolean): Promise<void> {
        await page.viewport(width, 900);
        const separator = url.includes('?') ? '&' : '?';
        await router.navigateByUrl(chat ? `${url}${separator}chat=new` : url);
        await settle();
        await transitions();
        await settle();
    }

    const q = <T extends Element = HTMLElement>(selector: string) => document.querySelector<T>(selector);
    const shown = (element: Element | null): element is HTMLElement =>
        element !== null && element.getClientRects().length > 0;

    function visibleColumns(view: SplitView): HTMLElement[] {
        const host = q(view.host);
        expect(host, `${view.host} rendered`).not.toBeNull();
        return view.columns
            // Not `:scope > .split`: the review wraps its split in the anchor rail.
            .map((selector) => host!.querySelector<HTMLElement>(`.split > ${selector}`))
            .filter(shown);
    }

    /** The page's own box: the main column's content box, which is what the splits key on. */
    function pageBox(): {left: number; right: number; width: number} {
        const main = q('main.content')!;
        const rect = main.getBoundingClientRect();
        const style = getComputedStyle(main);
        const left = rect.left + parseFloat(style.paddingLeft);
        const right = rect.right - parseFloat(style.paddingRight);
        return {left, right, width: right - left};
    }

    for (const width of [820, 1440] as const) {
        describe(`with the chat open at ${width}px`, () => {
            for (const view of VIEWS) {
                it(`keeps every column of ${view.name} whole, and the page does not scroll sideways`, async () => {
                    await open(view.url, width, true);
                    const panel = q('.lg-chat-panel');
                    expect(shown(panel), 'the chat is docked open').toBe(true);
                    const docked = panel!.getBoundingClientRect();
                    const box = pageBox();
                    expect(box.right).toBeLessThanOrEqual(docked.left + 1);

                    const columns = visibleColumns(view);
                    expect(columns.length).toBeGreaterThan(0);
                    for (const column of columns) {
                        const name = view.columns.find((selector) => column.matches(selector));
                        const rect = column.getBoundingClientRect();
                        // Inside the page's box, so the panel covers none of it.
                        expect(rect.left, `${name} left edge`).toBeGreaterThanOrEqual(box.left - 1);
                        expect(rect.right, `${name} right edge`).toBeLessThanOrEqual(box.right + 1);
                        // And its content fits it: a column cut to a sliver still has a box inside
                        // the page, it is what is drawn in it that runs out over its neighbours.
                        if (!column.matches(view.scroller ?? ':not(*)')) {
                            expect(column.scrollWidth, `${name} content width`).toBeLessThanOrEqual(
                                column.clientWidth + 1,
                            );
                        }
                    }

                    // Below the split's breakpoint on its own box: one column, whatever the window.
                    expect(columns.length, 'one column beside the panel').toBe(1);
                    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(width);
                });
            }
        });
    }

    /*
     * The shortlist's script reads the same breakpoint: it opens the first offer by itself only
     * while both columns are on screen, and roots the list's paging on the list pane only then.
     * Beside the panel the detail would replace the list the reader asked for. The chat is
     * opened on another screen first and follows the navigation, which is how it is used: a
     * cold load that answers the list before the chat's capability is a race this page does
     * not try to win.
     */
    it('opens no offer by itself beside the panel at 1440, and does without it', async () => {
        await open('/dashboard', 1440, true);
        expect(shown(q('.lg-chat-panel')), 'the chat is docked open').toBe(true);
        await router.navigateByUrl('/shortlist?chat=new');
        await settle();
        expect(new URL(router.url, 'http://x').pathname).toBe('/shortlist');
        expect(shown(q('lg-shortlist-page .list-col'))).toBe(true);

        // Closed by its own control: the chat keeps `?chat` across navigations (ISC-444), so a
        // URL without it is no longer a closed chat. The page box widens, the observer reads the
        // two-track grid, and the first offer opens.
        q<HTMLButtonElement>('.lg-chat-close')!.click();
        await settle();
        await transitions();
        await settle();
        expect(shown(q('.lg-chat-panel'))).toBe(false);
        expect(new URL(router.url, 'http://x').pathname).toBe('/shortlist/1');
    });

    /*
     * The other half of the claim: with the chat closed the page box is the window minus the
     * two gutters, and the splits break exactly where the viewport rule broke them — two
     * columns at 1152, one at 1151.
     */
    describe('with the chat closed', () => {
        for (const view of VIEWS) {
            it(`splits ${view.name} at 1152 and not at 1151, as before`, async () => {
                await open(view.url, 1152, false);
                expect(shown(q('.lg-chat-panel'))).toBe(false);
                expect(visibleColumns(view).length, 'two columns at 1152').toBe(2);

                await page.viewport(1151, 900);
                await settle();
                expect(visibleColumns(view).length, 'one column at 1151').toBe(1);
            });
        }

        it('keeps the shortlist two columns wide at 1440', async () => {
            await open('/shortlist/1', 1440, false);
            const columns = visibleColumns(VIEWS[0]);
            expect(columns.length).toBe(2);
            expect(pageBox().width).toBeGreaterThan(1300);
        });
    });
});
