import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting, TestRequest} from '@angular/common/http/testing';
import {WritableSignal} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {By} from '@angular/platform-browser';
import {provideRouter, Router, withComponentInputBinding} from '@angular/router';
import {cdp, page} from 'vitest/browser';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {ShortlistPage as ShortlistPayload} from '@core/model/shortlist-page';
import {TRANSITIONS} from '@core/model/transitions.fixture';
import {provideScoreThresholds} from '@core/store/score-thresholds.provider';
import {ShortlistStore} from '@core/store/shortlist.store';
import {App} from '../../app';
import {routes} from '../../app.routes';

/*
 * When a shortlist row's swipe must not move (spec 024): a mostly vertical or sub-slop touch, a tap
 * that still opens the offer and an engaged gesture that never does (ISC-494); a write in flight,
 * select mode and reduced motion (ISC-497). Against the real root, shell, page and store in a real
 * browser, every request answered here — the harness of `offer-swipe.browser.spec.ts`, which owns
 * the gesture's own claims. The device half of ISC-494 (the list really scrolls) is the operator's.
 */

function entry(id: number): ShortlistEntry {
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

const WORKING: ShortlistPayload = {
    entries: [entry(1), entry(2), entry(3)],
    nextCursor: null,
    matched: 3,
    unscored: 0,
    total: 3,
    portals: ['portal-a'],
    related: null,
    relatedTo: null,
};

/** While true an archive stays in flight: its PATCH is left unanswered. */
let holdWrites = false;

function answer(request: TestRequest): unknown {
    const url = request.request.url;
    const written = /^\/api\/v1\/offers\/(\d+)$/.exec(url);
    if (written && request.request.method === 'PATCH') {
        const stored = WORKING.entries.find((e) => e.offer.id === Number(written[1]))!;
        const archived = (request.request.body as {archived: boolean}).archived;
        return {...stored, offer: {...stored.offer, archivedAt: archived ? '2026-10-01T08:00:00Z' : null, archiveSource: archived ? 'MANUAL' : null}};
    }
    if (url === '/api/v1/offers') return WORKING;
    if (url === '/api/v1/offers/funnel') return {total: WORKING.total, stages: [], survived: WORKING.total, archived: 0};
    const offer = /^\/api\/v1\/offers\/(\d+)$/.exec(url);
    if (offer) return WORKING.entries.find((e) => e.offer.id === Number(offer[1])) ?? null;
    if (url === '/api/v1/applications') return [];
    if (url === '/api/v1/applications/lanes') return [];
    if (url === '/api/v1/applications/transitions') return TRANSITIONS;
    if (url === '/api/v1/sources/manual/pending') return [];
    if (url === '/api/v1/chat/capability') return {present: false};
    if (url === '/api/v1/chat/conversations') return [];
    return undefined;
}

/** The typed `CDPSession` carries no members; the provider's own session has `send()`. */
function setReducedMotion(reduced: boolean): Promise<unknown> {
    const session = cdp() as unknown as {send(method: string, params: {features: readonly {name: string; value: string}[]}): Promise<unknown>};
    return session.send('Emulation.setEmulatedMedia', {features: reduced ? [{name: 'prefers-reduced-motion', value: 'reduce'}] : []});
}

/** Where an unreleased drag stands, so the caller can lift it there. */
interface End {
    x: number;
    y: number;
    id: number;
}

describe('a shortlist row that must not swipe', () => {
    let fixture: ComponentFixture<App>;
    let http: HttpTestingController;
    let router: Router;
    let pointerId = 100;
    /** A virtual clock for the events' `timeStamp`: 50ms a step is a slow drag, never a fling. */
    let now = 0;

    beforeEach(async () => {
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
        await page.viewport(390, 900);
        await router.navigateByUrl('/shortlist');
        await settle();
    });

    afterEach(async () => {
        (fixture.nativeElement as HTMLElement).remove();
        holdWrites = false;
        await setReducedMotion(false);
    });

    const frame = () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));

    async function settle(): Promise<void> {
        for (let i = 0; i < 12; i++) {
            await new Promise((resolve) => setTimeout(resolve, 0));
            for (const request of http.match(() => true)) {
                if (request.cancelled || (holdWrites && request.request.method === 'PATCH')) continue;
                const body = answer(request);
                if (body === undefined) {
                    request.flush(null, {status: 404, statusText: 'Not in this spec'});
                } else {
                    request.flush(body);
                }
            }
            fixture.detectChanges();
        }
        await frame();
    }

    function row(index: number): HTMLLIElement {
        const rows = document.querySelectorAll<HTMLLIElement>('lg-shortlist-page .offer-list > li');
        expect(rows.length, 'the list rendered its rows').toBeGreaterThan(index);
        return rows[index];
    }

    const card = (li: HTMLElement) => li.querySelector<HTMLElement>('lg-offer-card')!;
    const title = (li: HTMLElement) => li.querySelector<HTMLAnchorElement>('.title a')!;
    const store = () => TestBed.inject(ShortlistStore);

    function translateX(li: HTMLElement): number {
        const transform = getComputedStyle(card(li)).transform;
        return transform === 'none' ? 0 : new DOMMatrixReadOnly(transform).m41;
    }

    function fire(target: Element, type: string, x: number, y: number, id: number): void {
        now += 50;
        const event = new PointerEvent(type, {
            pointerType: 'touch',
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

    /** A finger lands on the card's body and travels (dx, dy) in ten steps, without lifting. */
    async function drag(li: HTMLElement, dx: number, dy = 0): Promise<End> {
        const id = ++pointerId;
        const target = li.querySelector('.offer')!;
        const box = target.getBoundingClientRect();
        const x0 = box.left + box.width * 0.6;
        const y0 = box.top + box.height / 2;
        fire(target, 'pointerdown', x0, y0, id);
        for (let i = 1; i <= 10; i++) {
            fire(target, 'pointermove', x0 + (dx * i) / 10, y0 + (dy * i) / 10, id);
        }
        fixture.detectChanges();
        await frame();
        return {x: x0 + dx, y: y0 + dy, id};
    }

    async function release(li: HTMLElement, end: End): Promise<void> {
        fire(li.querySelector('.offer')!, 'pointerup', end.x, end.y, end.id);
        fixture.detectChanges();
        await frame();
    }

    describe('scrolling and tapping (ISC-494)', () => {
        it('hands vertical panning to the browser on every row', () => {
            for (const index of [0, 1, 2]) {
                expect(getComputedStyle(row(index)).touchAction).toBe('pan-y');
            }
        });

        it('moves nothing for a mostly vertical touch', async () => {
            const li = row(0);
            const end = await drag(li, -30, 90);
            expect(translateX(li)).toBe(0);
            expect(li.classList.contains('is-swiping')).toBe(false);
            await release(li, end);
        });

        it('moves nothing for a touch under the 10px slop', async () => {
            const li = row(0);
            const end = await drag(li, -9, 2);
            expect(translateX(li)).toBe(0);
            expect(li.classList.contains('is-swiping')).toBe(false);
            await release(li, end);
        });

        it('opens nothing after a gesture that engaged, and the next tap opens the offer', async () => {
            const li = row(0);
            const end = await drag(li, -60);
            expect(translateX(li), 'the gesture engaged').toBeLessThan(-30);
            await release(li, end);
            title(li).click();
            await settle();
            expect(router.url, 'the engaged gesture swallowed its click').toBe('/shortlist');

            const anchor = title(row(0));
            const box = anchor.getBoundingClientRect();
            const id = ++pointerId;
            fire(anchor, 'pointerdown', box.left + 4, box.top + 4, id);
            fire(anchor, 'pointerup', box.left + 4, box.top + 4, id);
            anchor.click();
            await settle();
            expect(router.url, 'a tap without travel opens the offer').toBe('/shortlist/1');
        });

        it('never lets the title be dragged away as a link', () => {
            const anchor = title(row(0));
            expect(anchor.getAttribute('draggable')).toBe('false');
            expect(getComputedStyle(anchor).getPropertyValue('-webkit-user-drag')).toBe('none');
        });
    });

    describe('when nothing moves (ISC-497)', () => {
        it('starts nothing on another row while an archive is in flight', async () => {
            holdWrites = true;
            const first = row(0);
            const end = await drag(first, -Math.round(0.45 * first.clientWidth) - 10);
            await release(first, end);
            await settle();
            // The release opens the row on its confirmation (ISC-501); its Archive sends the write.
            first.querySelector<HTMLButtonElement>('.swipe-commit')!.click();
            await settle();
            expect(store().archiving(), 'the archive is still out').toBe(1);

            const second = row(1);
            const next = await drag(second, -120);
            expect(translateX(second)).toBe(0);
            expect(second.classList.contains('is-swiping')).toBe(false);
            await release(second, next);
        });

        it('starts nothing while Select is on', async () => {
            const shortlist = fixture.debugElement.query(By.css('lg-shortlist-page')).componentInstance as {selecting: WritableSignal<boolean>};
            shortlist.selecting.set(true);
            await settle();
            const li = row(0);
            const end = await drag(li, -120);
            expect(translateX(li)).toBe(0);
            expect(li.classList.contains('is-swiping')).toBe(false);
            await release(li, end);
        });

        it('starts nothing on one row while another is picked', async () => {
            row(1).querySelector<HTMLInputElement>('.pick input')!.click();
            await settle();
            expect(store().pickedCount()).toBe(1);
            const li = row(0);
            const end = await drag(li, -120);
            expect(translateX(li)).toBe(0);
            await release(li, end);
        });

        it('follows a touch on a fine pointer that shows its checkboxes with nothing picked', async () => {
            const li = row(0);
            expect(li.querySelector('.pick input'), 'the fine pointer shows the checkbox').not.toBeNull();
            expect(store().pickedCount()).toBe(0);
            const end = await drag(li, -120);
            expect(translateX(li)).toBeLessThan(-60);
            await release(li, end);
        });

        describe('under prefers-reduced-motion', () => {
            beforeEach(async () => {
                await setReducedMotion(true);
                await frame();
                expect(matchMedia('(prefers-reduced-motion: reduce)').matches, 'the emulation took').toBe(true);
            });

            it('follows the finger and springs back without animating', async () => {
                const li = row(0);
                const end = await drag(li, -80);
                expect(translateX(li), 'the row still follows').toBeLessThan(-40);
                await release(li, end);
                expect(getComputedStyle(card(li)).transitionDuration).toBe('0s');
                expect(translateX(li), 'back in the same frame').toBe(0);
            });

            it('follows the finger and leaves without animating', async () => {
                holdWrites = true;
                const li = row(0);
                const end = await drag(li, -Math.round(0.45 * li.clientWidth) - 10);
                expect(translateX(li), 'the row still follows').toBeLessThan(-100);
                await release(li, end);
                await settle();
                // Settled open in the same frame, then sent off by its Archive (ISC-501).
                expect(li.classList.contains('is-open'), 'the row settled open').toBe(true);
                expect(getComputedStyle(card(li)).transitionDuration).toBe('0s');
                li.querySelector<HTMLButtonElement>('.swipe-commit')!.click();
                fixture.detectChanges();
                expect(li.classList.contains('is-leaving'), 'the row is leaving').toBe(true);
                expect(getComputedStyle(card(li)).transitionDuration).toBe('0s');
            });
        });
    });
});
