import {Component} from '@angular/core';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router, RouterLink, withComponentInputBinding} from '@angular/router';
import {Dispatcher} from '@ngrx/signals/events';
import {page, userEvent} from 'vitest/browser';
import {Subject} from 'rxjs';
import {ChatApi} from '@core/api/chat.api';
import {ChatEvent} from '@core/model/chat';
import {chatEvents} from '@core/store/chat.events';
import {ChatStore} from '@core/store/chat.store';
import {LG_ICONS} from '@shared/icon/lucide-icons';
import {App} from '../../app';

@Component({template: '<h1>screen</h1><p>A page the chat docks beside.</p>'})
class Screen {}

/** The shortlist as far as ISC-444 needs it: a page with an in-app link to an offer, written the way the cards write theirs. */
@Component({
    imports: [RouterLink],
    template: '<h1>shortlist</h1><a class="lg-test-offer" [routerLink]="[\'/shortlist\', 2291]">Kotlin backend</a>',
})
class Shortlist {}

const SCREENS = [390, 820, 1440] as const;

/**
 * The drawer's frame on the three screens the design names (ISC-432): the real root, shell,
 * header and the real panel, against a router whose screens are stand-ins, because what is
 * measured here is the frame and the URL, not a page.
 *
 * <p>In a real browser, because the claim is about layout — the page's box ending where the
 * panel's begins — and about focus leaving a non-modal panel by Tab, neither of which jsdom has.
 */
describe('the chat drawer (ISC-432)', () => {
    let fixture: ComponentFixture<App>;
    let http: HttpTestingController;
    let router: Router;

    beforeEach(async () => {
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                provideRouter(
                    [
                        {path: 'dashboard', component: Screen},
                        {path: 'shortlist', component: Shortlist},
                        {path: 'shortlist/:id', component: Screen},
                        // The rest of the navigation's destinations, so every entry lands somewhere (ISC-444).
                        ...['pipeline', 'analytics', 'workflow', 'sources'].map((path) => ({path, component: Screen})),
                    ],
                    withComponentInputBinding(),
                ),
            ],
        });
        http = TestBed.inject(HttpTestingController);
        router = TestBed.inject(Router);
        fixture = TestBed.createComponent(App);
        document.body.appendChild(fixture.nativeElement);
        await router.navigateByUrl('/dashboard');
        await settle();
        http.match((r) => r.url === '/api/v1/chat/capability').forEach((r) => r.flush({present: true}));
        await settle();
    });

    afterEach(() => (fixture.nativeElement as HTMLElement).remove());

    async function settle(): Promise<void> {
        for (let i = 0; i < 4; i++) {
            await new Promise((resolve) => setTimeout(resolve, 0));
            fixture.detectChanges();
        }
    }

    // The slide-ins end; the streaming caret and the busy marks loop for as long as a turn runs,
    // and waiting for those would wait for ever.
    async function transitions(): Promise<void> {
        const finite = document.getAnimations().filter((animation) => animation.effect?.getComputedTiming().iterations !== Infinity);
        await Promise.all(finite.map((animation) => animation.finished));
    }

    const q = <T extends Element = HTMLElement>(selector: string) => document.querySelector<T>(selector);
    const button = () => q<HTMLButtonElement>('.lg-chat-open');
    const panel = () => q('.lg-chat-panel');
    const isOpen = () => panel() !== null && (panel() as HTMLElement).getClientRects().length > 0;
    const chatParam = () => new URL(router.url, 'http://x').searchParams.get('chat');

    /** Opened, and the slide-in finished: a frame measured mid-slide is 100% off to the side. */
    async function openByButton(): Promise<void> {
        button()!.click();
        await settle();
        await transitions();
    }

    for (const width of SCREENS) {
        describe(`at ${width}px`, () => {
            beforeEach(async () => {
                await page.viewport(width, 900);
                await settle();
            });

            it('has a named button, and opens by it', async () => {
                expect(button()).not.toBeNull();
                expect(button()!.getAttribute('aria-label')).toBe('Ask the corpus');
                await openByButton();
                expect(isOpen()).toBe(true);
                expect(chatParam()).toBe('new');
                expect(panel()!.contains(document.activeElement)).toBe(true);
            });

            it('closes on Escape with focus inside, focus back on the button', async () => {
                await openByButton();
                (document.activeElement as HTMLElement).dispatchEvent(
                    new KeyboardEvent('keydown', {key: 'Escape', bubbles: true, cancelable: true}),
                );
                // A modal sheet hears Escape as `cancel`; a real key press sends both.
                q('dialog[open]')?.dispatchEvent(new Event('cancel', {cancelable: true}));
                await settle();
                expect(isOpen()).toBe(false);
                expect(chatParam()).toBeNull();
                expect(document.activeElement).toBe(button());
            });

            it('closes on its close button, focus back on the button', async () => {
                await openByButton();
                q<HTMLButtonElement>('.lg-chat-close')!.click();
                await settle();                expect(isOpen()).toBe(false);
                expect(document.activeElement).toBe(button());
            });

            if (width >= 768) {
                it('docks beside the page: the page ends where the panel begins, and no backdrop', async () => {
                    await openByButton();
                    const content = q('main.content')!.getBoundingClientRect();
                    const docked = panel()!.getBoundingClientRect();
                    expect(Math.abs(content.right - docked.left)).toBeLessThanOrEqual(1);
                    expect(docked.right).toBeLessThanOrEqual(width + 1);
                    expect(q('dialog[open]')).toBeNull();
                    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(width);
                });

                it('stays open with ?chat unchanged when a source is followed', async () => {
                    await openByButton();
                    const before = chatParam();
                    await router.navigate(['/shortlist', 2291], {queryParamsHandling: 'preserve'});
                    await settle();
                    expect(router.url.startsWith('/shortlist/2291')).toBe(true);
                    expect(isOpen()).toBe(true);
                    expect(chatParam()).toBe(before);
                });
            } else {
                it('is a full-screen sheet', async () => {
                    await openByButton();
                    const sheet = panel()!.getBoundingClientRect();
                    expect(q('dialog[open]')).not.toBeNull();
                    expect(Math.round(sheet.width)).toBe(width);
                    expect(sheet.left).toBe(0);
                });
            }
        });
    }

    it('lets focus leave the docked panel by Tab at 1440', async () => {
        await page.viewport(1440, 900);
        await openByButton();
        const focusables = panel()!.querySelectorAll<HTMLElement>('button:not([disabled]), textarea, a[href]');
        focusables[focusables.length - 1].focus();
        await userEvent.tab();
        expect(panel()!.contains(document.activeElement)).toBe(false);
    });

    it('folds into a bar above the bottom navigation when a source is followed at 390, and back on a tap (ISC-441)', async () => {
        await page.viewport(390, 900);
        await settle();
        const server = new Subject<ChatEvent>();
        vi.spyOn(TestBed.inject(ChatApi), 'ask').mockReturnValue(server);
        await router.navigateByUrl('/dashboard?chat=9');
        await settle();
        http.expectOne('/api/v1/chat/conversations/9').flush({
            id: 9,
            title: 'Kafka rates in the last month',
            pinnedOfferId: null,
            turns: [],
            updatedAt: '2026-09-27T08:00:00Z',
        });
        await settle();
        await transitions();

        const input = q<HTMLTextAreaElement>('.lg-chat-input')!;
        input.value = 'Which offers asked for Kafka?';
        input.dispatchEvent(new Event('input'));
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true}));
        await settle();
        server.next({event: 'turn', data: {turnId: 31}});
        server.next({event: 'text', data: {delta: 'Three offers named Kafka. '}});
        await settle();
        const before = chatParam();
        expect(before).toBe('9');

        // Following a source: a navigation that keeps `?chat`, below 48rem.
        await router.navigate(['/shortlist', 2291], {queryParamsHandling: 'preserve'});
        await settle();
        server.next({event: 'text', data: {delta: 'The first pays best. '}});
        await settle();

        const bar = q('.lg-chat-minibar');
        expect(bar).not.toBeNull();
        expect(q('dialog[open]')).toBeNull();
        expect(chatParam()).toBe(before);
        const box = bar!.getBoundingClientRect();
        const nav = q('.topnav')!.getBoundingClientRect();
        expect(Math.abs(box.bottom - nav.top)).toBeLessThanOrEqual(1);
        expect(box.left).toBe(0);
        expect(Math.round(box.width)).toBe(390);
        expect(bar!.textContent).toContain('Kafka rates in the last month');

        // The streaming mark is the living mark in its working frame, in the busy dot's old place,
        // its dots on the AI colour: a model is writing, nothing else (ISC-469).
        const mark = q('.lg-chat-minibar lg-living-mark');
        expect(mark).not.toBeNull();
        expect(mark!.getAttribute('data-frame')).toBe('working');
        expect(q('.lg-chat-minibar-busy')).toBeNull();
        const ai = document.createElement('span');
        ai.style.color = 'var(--lg-ai)';
        document.body.appendChild(ai);
        expect(getComputedStyle(mark!).color).toBe(getComputedStyle(ai).color);
        ai.remove();

        // The page it opened, visible above the bar and not under a sheet.
        const heading = q('main.content h1')!;
        const top = heading.getBoundingClientRect();
        expect(top.bottom).toBeLessThanOrEqual(box.top);
        expect(document.elementFromPoint(top.left + 2, top.top + top.height / 2)).toBe(heading);

        bar!.click();
        await settle();
        await transitions();

        expect(q('.lg-chat-minibar')).toBeNull();
        expect(q('dialog[open]')).not.toBeNull();
        expect(isOpen()).toBe(true);
        const turns = document.querySelectorAll('dialog[open] .lg-chat-turn');
        const last = turns[turns.length - 1];
        expect(last?.textContent).toContain('Which offers asked for Kafka?');
        expect(last?.textContent).toContain('The first pays best.');
        expect(router.url.startsWith('/shortlist/2291')).toBe(true);
        expect(chatParam()).toBe(before);

        server.next({event: 'done', data: {state: 'DONE'}});
        server.complete();
        await settle();
    });

    // ISC-469: the bar's mark lives exactly as long as the stream. Followed, streaming, ended,
    // all without the bar being tapped, so the bar is still there when the turn is done.
    it('shows the working mark in the bar while a stub streams at 390, and drops it once the stream ends (ISC-469)', async () => {
        await page.viewport(390, 900);
        await settle();
        const server = new Subject<ChatEvent>();
        vi.spyOn(TestBed.inject(ChatApi), 'ask').mockReturnValue(server);
        await router.navigateByUrl('/dashboard?chat=9');
        await settle();
        http.expectOne('/api/v1/chat/conversations/9').flush({
            id: 9,
            title: 'Kafka rates in the last month',
            pinnedOfferId: null,
            turns: [],
            updatedAt: '2026-09-27T08:00:00Z',
        });
        await settle();
        await transitions();

        const input = q<HTMLTextAreaElement>('.lg-chat-input')!;
        input.value = 'Which offers asked for Kafka?';
        input.dispatchEvent(new Event('input'));
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true}));
        await settle();
        server.next({event: 'turn', data: {turnId: 31}});
        server.next({event: 'text', data: {delta: 'Three offers named Kafka. '}});
        await settle();

        await router.navigate(['/shortlist', 2291], {queryParamsHandling: 'preserve'});
        await settle();
        server.next({event: 'text', data: {delta: 'The first pays best. '}});
        await settle();

        const bar = () => q('.lg-chat-minibar');
        const mark = () => q('.lg-chat-minibar lg-living-mark');
        expect(bar()).not.toBeNull();
        expect(mark()).not.toBeNull();
        expect(mark()!.getAttribute('data-frame')).toBe('working');
        expect(q("lg-living-mark[data-frame='working']")).toBe(mark());
        const ai = document.createElement('span');
        ai.style.color = 'var(--lg-ai)';
        document.body.appendChild(ai);
        expect(getComputedStyle(mark()!).color).toBe(getComputedStyle(ai).color);
        ai.remove();
        // The spoken busy state beside it stays as it was.
        expect(bar()!.textContent).toContain('an answer is being written');

        server.next({event: 'done', data: {state: 'DONE'}});
        server.complete();
        await settle();

        expect(bar()).not.toBeNull();
        expect(mark()).toBeNull();
        expect(bar()!.querySelector('lg-living-mark')).toBeNull();
    });

    // ISC-468: the empty chat's plate is the mark at rest in place of the sparkle, and the answer
    // glyph keeps its 1.5rem box, so neither the plate nor any answer's text moves. The reference
    // is today's build measured before the mark landed (specs/021-chat-living-mark/artifacts/
    // boxes-before.json, eb95d41, root font 15px); the three answers are its three texts.
    describe('the plate and the answers against the recorded boxes (ISC-468)', () => {
        const BEFORE = {
            390: {
                plate: {w: 45, h: 45},
                answers: [
                    {w: 328.13, h: 42.19},
                    {w: 328.13, h: 114.84},
                    {w: 328.13, h: 63.28},
                ],
            },
            1440: {
                plate: {w: 45, h: 45},
                answers: [
                    {w: 537.13, h: 42.19},
                    {w: 537.13, h: 93.75},
                    {w: 537.13, h: 42.19},
                ],
            },
        } as const;
        const OFFSET_IN_ROW = {dx: 31.88, dy: 0};
        const TEXTS = [
            'Fourteen remote Spring offers came in last month. The strongest is a Kotlin backend role.',
            'Three offers named Kafka.\n\n- The first pays best.\n- The second is fully remote.\n- The third starts in October and runs for six months with an option to extend.',
            'None of the applications you sent this quarter mention Terraform, but two adverts on the shortlist ask for it and both are still open.',
        ];

        /** The sparkle icon, known by its drawing: `lg-icon` does not reflect its name. */
        const SPARKLE = LG_ICONS.sparkles
            .filter((part) => part[0] === 'path')
            .map((part) => String(part[1]['d']));
        const sparklesIn = (root: Element) =>
            [...root.querySelectorAll('lg-icon')].filter((icon) =>
                [...icon.querySelectorAll('path')].some((path) => SPARKLE.includes(path.getAttribute('d') ?? '')),
            );

        const near = (actual: number, expected: number) => expect(Math.abs(actual - expected)).toBeLessThanOrEqual(1);

        for (const width of [390, 1440] as const) {
            it(`holds the mark at rest on the plate at ${width}, with no sparkle in the chat and the plate's box unchanged`, async () => {
                await page.viewport(width, 900);
                await settle();
                await router.navigateByUrl('/dashboard?chat=new');
                await settle();
                await transitions();
                http.match((r) => r.method === 'GET' && r.url === '/api/v1/chat/conversations').forEach((r) => r.flush([]));
                // The empty chat's suggestions (ISC-456), so the plate sits above real rows.
                http.match((r) => r.url === '/api/v1/chat/suggestions').forEach((r) => r.flush([{trigger: 'NEW_THIS_WEEK', text: 'What came in this week?', count: 12}]));
                await settle();

                const plate = q('.lg-chat-plate');
                expect(plate).not.toBeNull();
                const mark = plate!.querySelector('lg-living-mark');
                expect(mark).not.toBeNull();
                expect(mark!.getAttribute('data-frame')).toBe('rest');
                expect(plate!.querySelector('lg-icon')).toBeNull();
                // The probe knows a sparkle when it sees one: the suggestions' icons are there and are not.
                expect(panel()!.querySelectorAll('.lg-chat-suggestion lg-icon').length).toBeGreaterThan(0);
                expect(sparklesIn(panel()!)).toEqual([]);

                const box = plate!.getBoundingClientRect();
                expect(box.width).toBe(BEFORE[width].plate.w);
                expect(box.height).toBe(BEFORE[width].plate.h);
            });

            it(`keeps every answer's text box within 1 px of the recorded one at ${width}`, async () => {
                await page.viewport(width, 900);
                await settle();
                await router.navigateByUrl('/dashboard?chat=9');
                await settle();
                http.expectOne('/api/v1/chat/conversations/9').flush({
                    id: 9,
                    title: 'Three answers',
                    pinnedOfferId: null,
                    turns: TEXTS.map((answer, i) => ({
                        id: 90 + i,
                        question: `Question ${i + 1}?`,
                        answer,
                        state: 'DONE',
                        steps: [],
                        sources: [],
                        replacesTurnId: null,
                        model: null,
                        createdAt: '2026-09-27T08:00:00Z',
                    })),
                    updatedAt: '2026-09-27T08:00:00Z',
                });
                await settle();
                http.match((r) => r.method === 'GET' && r.url === '/api/v1/chat/conversations').forEach((r) => r.flush([]));
                await settle();
                await transitions();

                const rows = [...panel()!.querySelectorAll<HTMLElement>('.lg-chat-answer')];
                expect(rows.length).toBe(3);
                expect(sparklesIn(panel()!)).toEqual([]);
                rows.forEach((row, i) => {
                    expect(row.querySelector('.lg-chat-glyph lg-living-mark')?.getAttribute('data-frame')).toBe('rest');
                    const text = row.querySelector('.lg-chat-md')!.getBoundingClientRect();
                    const origin = row.getBoundingClientRect();
                    near(text.width, BEFORE[width].answers[i].w);
                    near(text.height, BEFORE[width].answers[i].h);
                    near(text.left - origin.left, OFFSET_IN_ROW.dx);
                    near(text.top - origin.top, OFFSET_IN_ROW.dy);
                });
            });
        }
    });

    // ISC-471: measured before the fix, the head, thread and composer laid out 707 px wide inside
    // the 390 px sheet, with the close control at x = 662 — off the screen, and one of the only three
    // ways to close the chat. Long turns and a long title are what widen a box that has no floor.
    describe('the mobile sheet (ISC-471)', () => {
        const LONG_TITLE = 'Remote Spring Boot offers from last month with Kafka, Kubernetes and an hourly rate';
        const LONG_QUESTION =
            'Which of the remote Spring Boot offers that came in last month asked for Kafka and Kubernetes, and which of them named an hourly rate?';
        const LONG_ANSWER =
            'Four of them did. The strongest is a Kotlin backend role that pays the best rate and starts in October.\n\n' +
            '- The first names Kafka Streams and a 95 € rate.\n' +
            '- The second is fully remote and runs for six months with an option to extend.\n' +
            '- The third lists https://example.org/a/very/long/path/that/never/breaks/anywhere/along/its/whole/length as its advert.';

        it('holds the head, thread and composer to the sheet at 390, both controls inside, nothing scrolling sideways', async () => {
            await page.viewport(390, 900);
            await settle();
            await router.navigateByUrl('/dashboard?chat=9');
            await settle();
            http.expectOne('/api/v1/chat/conversations/9').flush({
                id: 9,
                title: LONG_TITLE,
                pinnedOfferId: null,
                turns: [0, 1, 2].map((i) => ({
                    id: 90 + i,
                    question: LONG_QUESTION,
                    answer: LONG_ANSWER,
                    state: 'DONE',
                    steps: [],
                    sources: [],
                    replacesTurnId: null,
                    model: null,
                    createdAt: '2026-09-27T08:00:00Z',
                })),
                updatedAt: '2026-09-27T08:00:00Z',
            });
            await settle();
            await transitions();

            const sheet = q('.lg-chat-drawer')!.getBoundingClientRect();
            expect(q('dialog[open]')).not.toBeNull();
            expect(Math.round(sheet.width)).toBe(390);
            for (const part of ['.lg-chat-head', '.lg-chat-thread', '.lg-chat-composer']) {
                const box = q(part)!.getBoundingClientRect();
                expect({part, width: Math.round(box.width)}).toEqual({part, width: Math.round(sheet.width)});
                expect(Math.abs(box.left - sheet.left)).toBeLessThanOrEqual(1);
            }
            for (const control of ['.lg-chat-close', '.lg-chat-new']) {
                const box = q(control)!.getBoundingClientRect();
                expect({control, right: box.right <= 390, left: box.left >= 0}).toEqual({control, right: true, left: true});
            }
            expect(document.documentElement.scrollWidth).toBe(390);
            // A page-level check is blind to a scroller of its own: each one is asked directly.
            for (const scroller of ['.lg-chat-drawer', '.lg-chat-panel', '.lg-chat-thread']) {
                const box = q(scroller)!;
                expect({scroller, sideways: box.scrollWidth - box.clientWidth}).toEqual({scroller, sideways: 0});
            }
        });
    });

    // ISC-444: measured before the fix, the navigation's links dropped `?chat`, so the chat closed on
    // every change of screen. Every hop here is a real click on a link the chat did not write.
    describe('a change of screen (ISC-444)', () => {
        /** Every navigation entry, the brand link, then the shortlist's offer link: one real click each. */
        async function hops(after: (label: string) => void): Promise<void> {
            const entries = [...document.querySelectorAll<HTMLAnchorElement>('.topnav a')];
            expect(entries.length).toBeGreaterThanOrEqual(6);
            for (const entry of entries) {
                await userEvent.click(entry);
                await settle();
                after(entry.getAttribute('href') ?? '');
            }
            await userEvent.click(q('.brand-link')!);
            await settle();
            after('brand');
            await userEvent.click(q<HTMLAnchorElement>(".topnav a[href^='/shortlist']")!);
            await settle();
            await userEvent.click(q('.lg-test-offer')!);
            await settle();
            expect(router.url.startsWith('/shortlist/2291')).toBe(true);
            after('offer');
        }

        it('stays open with ?chat unchanged across every hop at 1440', async () => {
            await page.viewport(1440, 900);
            await settle();
            await openByButton();
            const before = chatParam();
            expect(before).toBe('new');
            await hops((hop) => {
                expect({hop, chat: chatParam(), open: isOpen()}).toEqual({hop, chat: before, open: true});
                expect(button()!.getAttribute('aria-expanded')).toBe('true');
            });
        });

        it('stays folded with ?chat unchanged across every hop at 390, and closes on its close control after', async () => {
            await page.viewport(390, 900);
            await settle();
            await openByButton();
            // The sheet covers the page and its navigation; following a source is what folds it.
            await router.navigate(['/shortlist', 2291], {queryParamsHandling: 'preserve'});
            await settle();
            const before = chatParam();
            expect(before).toBe('new');
            expect(q('.lg-chat-minibar')).not.toBeNull();
            await hops((hop) => {
                expect({hop, chat: chatParam(), folded: q('.lg-chat-minibar') !== null, sheet: q('dialog[open]') !== null}).toEqual({
                    hop,
                    chat: before,
                    folded: true,
                    sheet: false,
                });
            });

            // A close is the chat's own, and it stays closed: the next hop brings nothing back.
            q<HTMLElement>('.lg-chat-minibar')!.click();
            await settle();
            await transitions();
            q<HTMLButtonElement>('.lg-chat-close')!.click();
            await settle();
            expect(chatParam()).toBeNull();
            await userEvent.click(q<HTMLAnchorElement>(".topnav a[href^='/pipeline']")!);
            await settle();
            expect(chatParam()).toBeNull();
            expect(isOpen()).toBe(false);
            expect(q('.lg-chat-minibar')).toBeNull();
        });
    });

    // ISC-445: measured before the fix, the panel was 840 px at 1440 with the rail open (rail 225,
    // thread 614), and folding the rail kept it 840 and widened the thread to 839 — the answer's
    // line length jumped and the page got nothing back.
    describe('the rail at 80rem (ISC-445)', () => {
        const RAIL_KEY = 'lg-chat-rail';
        const forget = () => {
            try {
                localStorage.removeItem(RAIL_KEY);
            } catch {
                // No storage in this browser: the rail starts open either way.
            }
        };

        beforeEach(forget);
        afterEach(forget);

        it('narrows the panel by the rail when the rail folds, the thread unchanged and the page wider by the same', async () => {
            await page.viewport(1440, 900);
            await settle();
            await router.navigateByUrl('/dashboard?chat=9');
            await settle();
            http.expectOne('/api/v1/chat/conversations/9').flush({
                id: 9,
                title: 'Kafka rates in the last month',
                pinnedOfferId: null,
                turns: [
                    {
                        id: 90,
                        question: 'Which offers asked for Kafka?',
                        answer: 'Three offers named Kafka. The first pays best, the second is fully remote.',
                        state: 'DONE',
                        steps: [],
                        sources: [],
                        replacesTurnId: null,
                        model: null,
                        createdAt: '2026-09-27T08:00:00Z',
                    },
                ],
                updatedAt: '2026-09-27T08:00:00Z',
            });
            await settle();
            http.match((r) => r.method === 'GET' && r.url === '/api/v1/chat/conversations').forEach((r) =>
                r.flush([{id: 9, title: 'Kafka rates in the last month', updatedAt: '2026-09-27T08:00:00Z'}]),
            );
            await settle();
            await transitions();

            const measure = () => ({
                thread: q('.lg-chat-thread')!.getBoundingClientRect().width,
                panel: q('.lg-chat-drawer')!.getBoundingClientRect().width,
                page: q('main.content')!.getBoundingClientRect().width,
            });
            expect(q('.lg-chat-rail')).not.toBeNull();
            const rail = q('.lg-chat-rail')!.getBoundingClientRect().width;
            const open = measure();

            q<HTMLButtonElement>('.lg-chat-history')!.click();
            await settle();
            await transitions();
            expect(q('.lg-chat-rail')).toBeNull();
            const folded = measure();

            expect(Math.abs(folded.thread - open.thread)).toBeLessThanOrEqual(1);
            expect(Math.abs(open.panel - folded.panel - rail)).toBeLessThanOrEqual(1);
            expect(Math.abs(folded.page - open.page - rail)).toBeLessThanOrEqual(1);
            expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(1440);

            // And back: the rail returns, the thread still the same box.
            q<HTMLButtonElement>('.lg-chat-history')!.click();
            await settle();
            await transitions();
            const reopened = measure();
            expect(Math.abs(reopened.thread - open.thread)).toBeLessThanOrEqual(1);
            expect(Math.abs(reopened.panel - open.panel)).toBeLessThanOrEqual(1);
        });
    });

    it('starts a new conversation pinned to the offer it was opened from', async () => {
        await page.viewport(1440, 900);
        await router.navigateByUrl('/shortlist/2291');
        await settle();
        // What "Ask about this offer" dispatches; the button itself is in `offer-detail.spec.ts`.
        TestBed.inject(Dispatcher).dispatch(chatEvents.newRequested({pinnedOfferId: 2291}));
        await settle();
        expect(isOpen()).toBe(true);
        expect(chatParam()).toBe('new');
        expect(TestBed.inject(ChatStore).pinnedOfferId()).toBe(2291);
        expect(q('.lg-chat-chip')?.textContent).toContain('2291');

        vi.spyOn(TestBed.inject(ChatApi), 'ask').mockReturnValue(new Subject<ChatEvent>());
        const input = q<HTMLTextAreaElement>('.lg-chat-input')!;
        input.value = 'Does it fit?';
        input.dispatchEvent(new Event('input'));
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true}));
        await settle();
        // ISC-446: the chip stays through the first question, the create and a change of screen.
        expect(q('.lg-chat-chip')?.textContent).toContain('2291');
        const create = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/chat/conversations');
        expect(create.request.body).toEqual({context: [{kind: 'OFFER', offerId: 2291}]});
        create.flush({id: 12, title: '', pinnedOfferId: 2291, turns: [], updatedAt: '2026-09-27T08:00:00Z'});
        await settle();
        expect(chatParam()).toBe('12');
        expect(q('.lg-chat-chip')?.textContent).toContain('2291');

        await router.navigateByUrl('/dashboard');
        await settle();
        expect(chatParam()).toBe('12');
        expect(router.routerState.snapshot.root.queryParamMap.get('chatCtx')).toBe('o:2291');
        expect(q('.lg-chat-chip')?.textContent).toContain('2291');
        // The pinned chat's suggestions were cancelled by the first question (ISC-456); a cancelled request takes no answer.
        http.match((r) => r.method === 'GET' && r.url.startsWith('/api/')).filter((r) => !r.cancelled).forEach((r) => r.flush([]));
    });

    /** A first question under `?chat=new` at 1440, the rail's list read answered empty, the create answered untitled. */
    async function firstQuestion(question: string): Promise<Subject<ChatEvent>> {
        await page.viewport(1440, 900);
        await settle();
        const server = new Subject<ChatEvent>();
        vi.spyOn(TestBed.inject(ChatApi), 'ask').mockReturnValue(server);
        await router.navigateByUrl('/dashboard?chat=new');
        await settle();
        await transitions();
        http.match((r) => r.method === 'GET' && r.url === '/api/v1/chat/conversations').forEach((r) => r.flush([]));
        await settle();

        const input = q<HTMLTextAreaElement>('.lg-chat-input')!;
        input.value = question;
        input.dispatchEvent(new Event('input'));
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true}));
        await settle();
        http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/chat/conversations').flush({
            id: 21,
            title: '',
            pinnedOfferId: null,
            turns: [],
            updatedAt: '2026-09-27T08:00:00Z',
        });
        await settle();
        server.next({event: 'turn', data: {turnId: 210}});
        await settle();
        return server;
    }

    // Fix 4F-3: the heading is the drawer's accessible name, so it is never blank.
    it('names the drawer after the first question once its conversation is created', async () => {
        const server = await firstQuestion('Which offers asked for Kafka?');

        expect(chatParam()).toBe('21');
        const heading = q('#lg-chat-title')!;
        expect(heading.textContent!.trim()).toBe('Which offers asked for Kafka?');
        const labelled = q('[aria-labelledby="lg-chat-title"]');
        expect(labelled).not.toBeNull();
        expect(document.getElementById(labelled!.getAttribute('aria-labelledby')!)!.textContent!.trim()).not.toBe('');

        server.next({event: 'done', data: {state: 'DONE'}});
        server.complete();
        await settle();
    });

    // Fix 4F-4: the rail read its list once when the drawer opened; the conversation created since is on it, and current.
    it('lists a conversation created during the visit in the 80rem rail, marked current', async () => {
        const server = await firstQuestion('Which offers asked for Kafka?');

        const current = q('.lg-chat-rail .lg-chat-row[aria-current="true"]');
        expect(current).not.toBeNull();
        expect(current!.textContent).toContain('Which offers asked for Kafka?');

        server.next({event: 'done', data: {state: 'DONE'}});
        server.complete();
        await settle();
    });

    // Spec 020: ⌘K on macOS, Ctrl+K elsewhere — from any screen, straight into the composer, and
    // with the chat already open, back into it. The combination is the one this browser's platform uses.
    describe('the keyboard shortcut (ISC-461)', () => {
        const mac = /mac|iphone|ipad|ipod/i.test(
            (navigator as Navigator & {userAgentData?: {platform?: string}}).userAgentData?.platform || navigator.platform,
        );
        const press = async () => {
            const target = (document.activeElement as HTMLElement | null) ?? document.body;
            const event = new KeyboardEvent('keydown', {key: 'k', metaKey: mac, ctrlKey: !mac, bubbles: true, cancelable: true});
            target.dispatchEvent(event);
            await settle();
            await transitions();
            return event;
        };
        const composer = () => q<HTMLTextAreaElement>('.lg-chat-input');

        for (const width of SCREENS) {
            it(`opens from three screens with focus in the composer, and refocuses it when open, at ${width}px`, async () => {
                await page.viewport(width, 900);
                await settle();
                for (const path of ['/dashboard', '/shortlist', '/sources']) {
                    await router.navigateByUrl(path);
                    await settle();
                    expect(isOpen()).toBe(false);

                    expect((await press()).defaultPrevented).toBe(true);
                    expect(isOpen()).toBe(true);
                    expect(chatParam()).toBe('new');
                    expect(document.activeElement).toBe(composer());

                    // Focus elsewhere — on the page where the panel docks, on its own head where it is a sheet.
                    const away = width >= 768 ? q<HTMLElement>('.settings-button')! : q<HTMLElement>('#lg-chat-title')!;
                    away.focus();
                    expect(document.activeElement).toBe(away);
                    await press();
                    expect(isOpen()).toBe(true);
                    expect(document.activeElement).toBe(composer());

                    q<HTMLButtonElement>('.lg-chat-close')!.click();
                    await settle();
                }
            });
        }
    });
});
