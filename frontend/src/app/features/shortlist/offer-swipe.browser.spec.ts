import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting, TestRequest} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router, withComponentInputBinding} from '@angular/router';
import {page} from 'vitest/browser';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {ShortlistPage as ShortlistPayload} from '@core/model/shortlist-page';
import {TRANSITIONS} from '@core/model/transitions.fixture';
import {provideScoreThresholds} from '@core/store/score-thresholds.provider';
import {App} from '../../app';
import {routes} from '../../app.routes';

/*
 * The swipe on a shortlist row (spec 024), against the real root, shell and page in a real
 * browser, because the claims are about a transform the renderer computes and a layer it paints.
 * The store is the real one; every request it makes is answered here, as in
 * `shortlist.browser.spec.ts`.
 */

function entry(id: number, archived: boolean, packageDir: string | null = null): ShortlistEntry {
    return {
        offer: {
            id,
            sourceName: 'sample-newsletter',
            externalId: `https://example.invalid/${id}`,
            title: `Senior Java Developer (m/w/d) ${id}`,
            description: 'Ablösung eines Monolithen durch eine ereignisgetriebene Architektur mit Kafka.',
            url: `https://example.invalid/${id}`,
            location: 'Köln',
            portal: 'portal-a',
            agency: 'Agentur Beispiel GmbH',
            publishedOn: '2026-09-01',
            tags: ['Java', 'Spring Boot', 'Kafka'],
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
            fullText: 'Wir suchen einen erfahrenen Entwickler.',
            packageDir,
            ingestedAt: '2026-09-02T05:12:00Z',
            archivedAt: archived ? '2026-09-20T08:00:00Z' : null,
            archiveSource: archived ? 'MANUAL' : null,
            enrichmentNote: null,
        },
        score: {value: 88, hardPass: true, reasons: [], model: null, rulesetVersion: '1'},
        flags: {incomplete: false, remoteUnknown: false, possibleDuplicate: false},
        sources: [{portal: 'portal-a', agency: null, url: `https://example.invalid/${id}`}],
        content: [],
    };
}

function payload(entries: readonly ShortlistEntry[]): ShortlistPayload {
    return {
        entries,
        nextCursor: null,
        matched: entries.length,
        unscored: 0,
        total: entries.length,
        portals: ['portal-a'],
        related: null,
        relatedTo: null,
    };
}

const WORKING = payload([entry(1, false), entry(2, false), entry(3, false, 'packages/3')]);
const ARCHIVE = payload([entry(1, true), entry(2, true), entry(3, true, 'packages/3')]);

let offersPayload: ShortlistPayload = WORKING;

/** Every archive or restore the store sent, in order: the PATCH the detail's button sends too. */
let writes: {id: number; archived: boolean}[] = [];

/** While true the server refuses every archive or restore, after it was counted in `writes`. */
let refuseWrites = false;

function answer(request: TestRequest): unknown {
    const url = request.request.url;
    const written = /^\/api\/v1\/offers\/(\d+)$/.exec(url);
    if (written && request.request.method === 'PATCH') {
        const archived = (request.request.body as {archived: boolean}).archived;
        writes.push({id: Number(written[1]), archived});
        const stored = offersPayload.entries.find((e) => e.offer.id === Number(written[1]))!;
        return {...stored, offer: {...stored.offer, archivedAt: archived ? '2026-10-01T08:00:00Z' : null, archiveSource: archived ? 'MANUAL' : null}};
    }
    if (url === '/api/v1/offers') return offersPayload;
    if (url === '/api/v1/offers/funnel') return {total: offersPayload.total, stages: [], survived: offersPayload.total, archived: 0};
    const offer = /^\/api\/v1\/offers\/(\d+)$/.exec(url);
    if (offer) return offersPayload.entries.find((e) => e.offer.id === Number(offer[1])) ?? null;
    if (url === '/api/v1/applications') return [];
    if (url === '/api/v1/applications/lanes') return [];
    if (url === '/api/v1/applications/transitions') return TRANSITIONS;
    if (url === '/api/v1/sources/manual/pending') return [];
    if (url === '/api/v1/chat/capability') return {present: false};
    if (url === '/api/v1/chat/conversations') return [];
    return undefined;
}

type Pointer = 'touch' | 'mouse' | 'pen';

describe('a shortlist row swiped to the left (ISC-492)', () => {
    let fixture: ComponentFixture<App>;
    let http: HttpTestingController;
    let router: Router;
    let pointerId = 10;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                provideRouter(routes, withComponentInputBinding()),
                provideScoreThresholds(),
            ],
        });
        http = TestBed.inject(HttpTestingController);
        router = TestBed.inject(Router);
        fixture = TestBed.createComponent(App);
        document.body.appendChild(fixture.nativeElement);
    });

    afterEach(() => {
        (fixture.nativeElement as HTMLElement).remove();
        offersPayload = WORKING;
        writes = [];
        refuseWrites = false;
    });

    const frame = () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));

    async function settle(): Promise<void> {
        for (let i = 0; i < 12; i++) {
            await new Promise((resolve) => setTimeout(resolve, 0));
            for (const request of http.match(() => true)) {
                if (request.cancelled) continue;
                const body = answer(request);
                if (refuseWrites && request.request.method === 'PATCH') {
                    request.flush(null, {status: 500, statusText: 'Refused'});
                } else if (body === undefined) {
                    request.flush(null, {status: 404, statusText: 'Not in this spec'});
                } else {
                    request.flush(body);
                }
            }
            fixture.detectChanges();
        }
        await frame();
    }

    async function open(url: string, width: number): Promise<void> {
        await page.viewport(width, 900);
        await router.navigateByUrl(url);
        await settle();
    }

    /** The `<li>` of the n-th row of the list, 0-based. */
    function row(index: number): HTMLLIElement {
        const rows = document.querySelectorAll<HTMLLIElement>('lg-shortlist-page .offer-list > li');
        expect(rows.length, 'the list rendered its rows').toBeGreaterThan(index);
        return rows[index];
    }

    const card = (li: HTMLElement) => li.querySelector<HTMLElement>('lg-offer-card')!;
    const reveal = (li: HTMLElement) => li.querySelector<HTMLElement>('.swipe-reveal');

    /** The card's horizontal translation, 0 when it has no transform at all. */
    function translateX(li: HTMLElement): number {
        const transform = getComputedStyle(card(li)).transform;
        return transform === 'none' ? 0 : new DOMMatrixReadOnly(transform).m41;
    }

    /** A virtual clock for the events' `timeStamp`, so a drag's speed is the test's choice, not the runner's. */
    let now = 0;

    /** Dispatches one pointer event, stamped `stepMs` after the previous one: 50ms is a slow drag. */
    function fire(target: Element, type: string, type_: Pointer, x: number, y: number, id: number, stepMs = 50): void {
        now += stepMs;
        const event = new PointerEvent(type, {
                pointerType: type_,
                pointerId: id,
                isPrimary: true,
                button: type === 'pointermove' ? -1 : 0,
                buttons: type === 'pointerup' ? 0 : 1,
                clientX: x,
                clientY: y,
                bubbles: true,
                cancelable: true,
                composed: true,
            });
        Object.defineProperty(event, 'timeStamp', {value: now});
        target.dispatchEvent(event);
    }

    /**
     * A finger lands on the card's body and travels `dx` in steps of 10px, without lifting.
     * Returns the pointer's end so the caller can release it.
     */
    async function drag(li: HTMLElement, pointer: Pointer, dx: number): Promise<{x: number; y: number; id: number}> {
        const id = ++pointerId;
        const target = li.querySelector('.offer')!;
        const box = target.getBoundingClientRect();
        const x0 = box.left + box.width * 0.6;
        const y = box.top + box.height / 2;
        fire(target, 'pointerdown', pointer, x0, y, id);
        const steps = Math.abs(dx) / 10;
        for (let i = 1; i <= steps; i++) {
            fire(target, 'pointermove', pointer, x0 + Math.sign(dx) * 10 * i, y, id);
        }
        fixture.detectChanges();
        await frame();
        return {x: x0 + dx, y, id};
    }

    async function release(li: HTMLElement, pointer: Pointer, end: {x: number; y: number; id: number}): Promise<void> {
        fire(li.querySelector('.offer')!, 'pointerup', pointer, end.x, end.y, end.id);
        fixture.detectChanges();
        await frame();
    }

    /** Continues an unreleased drag by `more` px, from where it stands. */
    async function further(li: HTMLElement, pointer: Pointer, end: {x: number; y: number; id: number}, more: number) {
        const target = li.querySelector('.offer')!;
        for (let i = 1; i <= Math.abs(more) / 10; i++) {
            fire(target, 'pointermove', pointer, end.x + Math.sign(more) * 10 * i, end.y, end.id);
        }
        fixture.detectChanges();
        await frame();
        return {...end, x: end.x + more};
    }

    function revealShown(li: HTMLElement): boolean {
        const layer = reveal(li);
        return layer !== null && getComputedStyle(layer).visibility === 'visible';
    }

    const sides = [
        {side: 'the working side', url: '/shortlist', data: WORKING, icon: 'archive', label: 'Archive', pkgLabel: 'Archive…'},
        {side: 'the archive side', url: '/shortlist?archived=1', data: ARCHIVE, icon: 'archive-restore', label: 'Restore', pkgLabel: 'Restore…'},
    ] as const;

    for (const width of [390, 1440] as const) {
        for (const s of sides) {
            describe(`on ${s.side} at ${width}px`, () => {
                beforeEach(async () => {
                    offersPayload = s.data;
                    await open(s.url, width);
                });

                it('paints nothing behind a row at rest', () => {
                    const li = row(0);
                    expect(translateX(li)).toBe(0);
                    expect(revealShown(li), 'the reveal is not painted at rest').toBe(false);
                });

                it(`follows a left touch drag with the finger and reveals ${s.label} behind the row`, async () => {
                    const li = row(0);
                    let end = await drag(li, 'touch', -80);
                    const first = translateX(li);
                    expect(first, 'the row moved left').toBeLessThan(-60);
                    expect(first, 'no further than the finger').toBeGreaterThanOrEqual(-80);

                    end = await further(li, 'touch', end, -40);
                    expect(translateX(li), 'one to one with the finger').toBeCloseTo(first - 40, 0);

                    expect(revealShown(li), 'the reveal is painted').toBe(true);
                    const layer = reveal(li)!;
                    expect(layer.getAttribute('aria-hidden')).toBe('true');
                    expect(layer.querySelector(`lg-icon[name="${s.icon}"]`), `the ${s.icon} glyph`).not.toBeNull();
                    expect(layer.textContent?.trim()).toBe(s.label);

                    // Dragged back past where it started: clamped at 0, no give to the right.
                    end = await further(li, 'touch', end, 200);
                    expect(translateX(li)).toBe(0);

                    await release(li, 'touch', end);
                    expect(translateX(li)).toBe(0);
                    expect(revealShown(li)).toBe(false);
                });

                it(`labels a row whose offer has a package ${s.pkgLabel}`, async () => {
                    const li = row(2);
                    const end = await drag(li, 'touch', -80);
                    expect(reveal(li)!.textContent?.trim()).toBe(s.pkgLabel);
                    await release(li, 'touch', end);
                });

                it('leaves the row where it is on a right touch drag', async () => {
                    const li = row(0);
                    const end = await drag(li, 'touch', 120);
                    expect(translateX(li)).toBe(0);
                    expect(li.classList.contains('is-swiping')).toBe(false);
                    expect(revealShown(li)).toBe(false);
                    await release(li, 'touch', end);
                });

                for (const pointer of ['mouse', 'pen'] as const) {
                    it(`never starts the gesture for a ${pointer}`, async () => {
                        const li = row(0);
                        const end = await drag(li, pointer, -120);
                        expect(translateX(li)).toBe(0);
                        expect(li.classList.contains('is-swiping')).toBe(false);
                        expect(revealShown(li)).toBe(false);
                        await release(li, pointer, end);
                    });
                }
            });
        }
    }

    /**
     * A finger drags the row until `uncovered` px of it are uncovered (the 10px slop added to the
     * travel), in steps of at most 10px `stepMs` apart, and lifts right there.
     */
    async function swipe(li: HTMLElement, uncovered: number, stepMs = 50): Promise<void> {
        const id = ++pointerId;
        const target = li.querySelector('.offer')!;
        const box = target.getBoundingClientRect();
        const x0 = box.left + box.width * 0.6;
        const y = box.top + box.height / 2;
        const travel = uncovered + 10;
        fire(target, 'pointerdown', 'touch', x0, y, id);
        for (let moved = 0; moved < travel; ) {
            moved = Math.min(travel, moved + 10);
            fire(target, 'pointermove', 'touch', x0 - moved, y, id, stepMs);
        }
        fire(target, 'pointerup', 'touch', x0 - travel, y, id, stepMs);
        fixture.detectChanges();
        await settle();
    }

    const titles = () =>
        [...document.querySelectorAll<HTMLElement>('lg-shortlist-page .offer-list > li .offer')].map((el) => el.textContent ?? '');

    async function expectBack(li: HTMLElement): Promise<void> {
        await settle();
        await expect.poll(() => translateX(li), {timeout: 2000}).toBe(0);
        // The spring-back ends on a timer; the fixture renders only when asked.
        await expect
            .poll(
                () => {
                    fixture.detectChanges();
                    return li.classList.contains('is-swiping');
                },
                {timeout: 2000},
            )
            .toBe(false);
        expect(writes, 'nothing was sent').toEqual([]);
        expect(document.querySelectorAll('lg-shortlist-page .offer-list > li').length, 'every row is still listed').toBe(3);
    }

    async function expectGone(li: HTMLElement, archived: boolean): Promise<void> {
        await settle();
        expect(writes, 'exactly one request, the one the detail button sends').toEqual([{id: 1, archived}]);
        await expect.poll(() => li.isConnected, {timeout: 2000}).toBe(false);
        expect(titles().some((t) => t.includes('Senior Java Developer (m/w/d) 1')), 'the row left the list').toBe(false);
        expect(titles()).toHaveLength(2);
    }

    describe('released (ISC-493)', () => {
        it('springs back and sends nothing at 30 % of a 390 row', async () => {
            await open('/shortlist', 390);
            const li = row(0);
            await swipe(li, Math.round(0.3 * li.clientWidth));
            await expectBack(li);
        });

        it('slides out and archives exactly once at 45 % of a 390 row', async () => {
            await open('/shortlist', 390);
            const li = row(0);
            await swipe(li, Math.round(0.45 * li.clientWidth));
            await expectGone(li, true);
        });

        it('slides out and restores exactly once at 45 % of a 390 row on the archive side', async () => {
            offersPayload = ARCHIVE;
            await open('/shortlist?archived=1', 390);
            const li = row(0);
            await swipe(li, Math.round(0.45 * li.clientWidth));
            await expectGone(li, false);
        });

        describe('on an 820 row, where 12rem caps the threshold', () => {
            beforeEach(async () => {
                await open('/shortlist', 820);
                expect(0.4 * row(0).clientWidth, 'the cap binds, not the share').toBeGreaterThan(13 * 16);
            });

            it('springs back and sends nothing at 11rem', async () => {
                const li = row(0);
                await swipe(li, 11 * 16);
                await expectBack(li);
            });

            it('slides out and archives exactly once at 13rem', async () => {
                const li = row(0);
                await swipe(li, 13 * 16);
                await expectGone(li, true);
            });
        });

        describe('flicked faster than the fling speed', () => {
            beforeEach(async () => {
                await open('/shortlist', 820);
            });

            it('springs back and sends nothing with 2rem uncovered', async () => {
                const li = row(0);
                await swipe(li, 2 * 16, 4);
                await expectBack(li);
            });

            it('slides out and archives exactly once with 4rem uncovered', async () => {
                const li = row(0);
                await swipe(li, 4 * 16, 4);
                await expectGone(li, true);
            });
        });
    });

    /** The single-offer confirmation, the one `a` opens for an offer with a package. */
    const confirmOne = () => document.querySelector<HTMLDialogElement>('dialog[aria-labelledby="lg-confirm-one-title"]')!;
    const dialogButton = (name: string) =>
        [...confirmOne().querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === name)!;

    describe('released past the threshold on a row whose offer has a package (ISC-495)', () => {
        beforeEach(async () => {
            await open('/shortlist', 390);
        });

        it('springs back and asks first; Cancel changes nothing; confirm archives once', async () => {
            let li = row(2);
            await swipe(li, Math.round(0.45 * li.clientWidth));
            await expectBack(li);
            expect(confirmOne().open, 'the confirmation `a` opens is open').toBe(true);
            expect(confirmOne().textContent).toContain('Archive this offer and its package?');

            dialogButton('Cancel').click();
            await settle();
            expect(confirmOne().open, 'Cancel closed it').toBe(false);
            expect(writes, 'Cancel sent nothing').toEqual([]);
            expect(titles()).toHaveLength(3);
            expect(titles()[2]).toContain('(m/w/d) 3');
            expect(translateX(row(2))).toBe(0);

            li = row(2);
            await swipe(li, Math.round(0.45 * li.clientWidth));
            await settle();
            expect(confirmOne().open).toBe(true);
            dialogButton('Archive').click();
            await settle();
            expect(writes, 'exactly one request after confirming').toEqual([{id: 3, archived: true}]);
            await expect.poll(() => li.isConnected, {timeout: 2000}).toBe(false);
            expect(titles()).toHaveLength(2);
        });
    });

    describe('a refused write after a swipe (ISC-495)', () => {
        it('springs back with one alert inside the row, none in the detail, cleared by the next touch', async () => {
            await open('/shortlist/1', 1440);
            refuseWrites = true;
            const li = row(0);
            await swipe(li, 13 * 16);
            await settle();
            expect(writes).toEqual([{id: 1, archived: true}]);
            await expect.poll(() => translateX(li), {timeout: 2000}).toBe(0);
            expect(li.isConnected, 'the row stayed').toBe(true);

            const alerts = li.querySelectorAll('[role="alert"]');
            expect(alerts.length, 'one alert inside the row').toBe(1);
            expect(alerts[0].textContent?.trim()).toBe('The archive could not be changed.');
            const detail = document.querySelector('lg-shortlist-page .detail-pane')!;
            expect(detail.textContent, 'the detail does not repeat it').not.toContain('The archive could not be changed.');

            const target = li.querySelector('.offer')!;
            fire(target, 'pointerdown', 'touch', 10, 10, ++pointerId);
            fixture.detectChanges();
            expect(li.querySelectorAll('[role="alert"]').length, 'the next touch clears it').toBe(0);
            fire(target, 'pointercancel', 'touch', 10, 10, pointerId);
        });
    });

    describe('the split-view rule (ISC-499)', () => {
        const LONG = 'Wir suchen einen erfahrenen Entwickler für eine ereignisgetriebene Plattform. '.repeat(120);
        const MANY = payload(
            [1, 2, 3, 4, 5, 6].map((id) => {
                const e = entry(id, false);
                return {...e, offer: {...e.offer, fullText: LONG}};
            }),
        );
        const detailPane = () => document.querySelector<HTMLElement>('lg-shortlist-page .detail-pane')!;
        const path = () => router.url.split('?')[0];

        it('at 1440 the open row hands the detail to its neighbour; another row leaves it alone', async () => {
            offersPayload = MANY;
            await page.viewport(1440, 500);
            await router.navigateByUrl('/shortlist/2');
            await settle();
            expect(detailPane().textContent).toContain('(m/w/d) 2');

            await swipe(row(1), 13 * 16);
            await settle();
            expect(writes).toEqual([{id: 2, archived: true}]);
            await expect.poll(() => path(), {timeout: 2000}).toBe('/shortlist/3');
            await settle();
            expect(detailPane().textContent, 'the detail shows the neighbour below').toContain('(m/w/d) 3');

            // Whatever box scrolls the detail, its own or one of its ancestors.
            let pane: HTMLElement = document.querySelector<HTMLElement>('lg-shortlist-page lg-offer-detail')!;
            while (pane.parentElement !== null && !(['auto', 'scroll'].includes(getComputedStyle(pane).overflowY) && pane.scrollHeight > pane.clientHeight)) {
                pane = pane.parentElement;
            }
            expect(pane.scrollHeight, 'the detail can scroll').toBeGreaterThan(pane.clientHeight + 150);
            pane.scrollTop = 150;
            const scrolled = pane.scrollTop;

            await swipe(row(4), 13 * 16);
            await settle();
            expect(writes).toEqual([
                {id: 2, archived: true},
                {id: 6, archived: true},
            ]);
            expect(path(), 'the selection stayed').toBe('/shortlist/3');
            expect(detailPane().textContent, 'the detail still shows offer 3').toContain('(m/w/d) 3');
            expect(detailPane().textContent).not.toContain('(m/w/d) 6');
            expect(pane.scrollTop, 'and its scroll position').toBe(scrolled);
        });

        it('in one column a swipe never navigates', async () => {
            await open('/shortlist', 390);
            await swipe(row(0), Math.round(0.45 * row(0).clientWidth));
            await settle();
            expect(writes).toEqual([{id: 1, archived: true}]);
            expect(path()).toBe('/shortlist');
        });
    });
});
