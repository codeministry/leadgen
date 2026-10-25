import {Component} from '@angular/core';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {cdp, page} from 'vitest/browser';
import {Subject} from 'rxjs';
import {ChatApi} from '@core/api/chat.api';
import {RECORDED_MODELS} from '@core/api/chat-stub';
import {ChatEvent} from '@core/model/chat';
import {App} from '../../../app';

@Component({template: '<h1>screen</h1>'})
class Screen {}

const CHUNKS = 40;
/** Long enough that forty of them overflow the thread several times over. */
const chunk = (i: number) => `Chunk ${i}: the rate sits between ninety and a hundred and ten euros an hour, remote, twelve months. `;

/**
 * Following the stream (ISC-462): the thread keeps the newest text in view only while the reader is at
 * its bottom. Scrolled up, the position holds and a neutral "Jump to latest" appears; pressing it lands
 * at the end and following resumes.
 *
 * <p>In a real browser, because the claim is scroll geometry — `scrollTop`, `scrollHeight` and a
 * scroll event the reader causes — none of which jsdom lays out.
 */
describe('the chat thread while a turn streams (ISC-462)', () => {
    let fixture: ComponentFixture<App>;
    let http: HttpTestingController;
    let server: Subject<ChatEvent>;

    beforeEach(async () => {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{path: 'dashboard', component: Screen}])],
        });
        http = TestBed.inject(HttpTestingController);
        server = new Subject<ChatEvent>();
        vi.spyOn(TestBed.inject(ChatApi), 'ask').mockReturnValue(server);
        await page.viewport(1280, 700);
        fixture = TestBed.createComponent(App);
        document.body.appendChild(fixture.nativeElement);
        await TestBed.inject(Router).navigateByUrl('/dashboard?chat=9');
        await settle();
        http.match((r) => r.url === '/api/v1/chat/capability').forEach((r) => r.flush({present: true}));
        await settle();
        http.expectOne('/api/v1/chat/conversations/9').flush({
            id: 9,
            title: 'Rates',
            pinnedOfferId: null,
            turns: [],
            updatedAt: '2026-09-27T08:00:00Z',
        });
        await settle();
        await drawerLoaded();

        const input = document.querySelector<HTMLTextAreaElement>('.lg-chat-input')!;
        input.value = 'What do Kafka offers pay?';
        input.dispatchEvent(new Event('input'));
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true}));
        await settle();
        server.next({event: 'turn', data: {turnId: 31}});
        await settle();
    });

    afterEach(() => {
        server.complete();
        (fixture.nativeElement as HTMLElement).remove();
    });

    async function settle(): Promise<void> {
        for (let i = 0; i < 4; i++) {
            await new Promise((resolve) => setTimeout(resolve, 0));
            fixture.detectChanges();
        }
        await new Promise((resolve) => requestAnimationFrame(resolve));
    }

    /**
     * The drawer is an `@defer` block, so its panel arrives with a dynamic import rather than after a
     * fixed number of ticks. Under Vitest 5 the first file to ask for the chunk can need longer than
     * `settle()` gives it, so this waits for the panel itself.
     */
    async function drawerLoaded(): Promise<void> {
        await vi.waitFor(() => {
            fixture.detectChanges();
            expect(document.querySelector('lg-chat-panel'), 'the chat drawer loaded').not.toBeNull();
        });
        await settle();
    }

    const thread = () => document.querySelector<HTMLElement>('.lg-chat-thread')!;
    const fromBottom = () => thread().scrollHeight - thread().scrollTop - thread().clientHeight;
    const jump = () => document.querySelector<HTMLButtonElement>('.lg-chat-jump');

    async function send(i: number): Promise<void> {
        server.next({event: 'text', data: {delta: chunk(i)}});
        await settle();
    }

    it('follows every chunk while the reader stays at the bottom, with no button', async () => {
        for (let i = 0; i < CHUNKS; i++) {
            await send(i);
            expect(fromBottom(), `after chunk ${i}`).toBeLessThanOrEqual(2);
            expect(jump()).toBeNull();
        }
        // The run really overflowed, or "at the bottom" would say nothing.
        expect(thread().scrollHeight).toBeGreaterThan(thread().clientHeight * 2);
    });

    it('holds the position once scrolled up 300px, shows the button, and resumes after it is pressed', async () => {
        for (let i = 0; i < CHUNKS / 2; i++) await send(i);
        expect(fromBottom()).toBeLessThanOrEqual(2);

        thread().scrollTop -= 300;
        thread().dispatchEvent(new Event('scroll'));
        await settle();
        const held = thread().scrollTop;

        for (let i = CHUNKS / 2; i < CHUNKS; i++) {
            await send(i);
            expect(thread().scrollTop, `after chunk ${i}`).toBe(held);
            expect(jump()).not.toBeNull();
        }
        const button = jump()!;
        expect(button.textContent?.trim()).toBe('Jump to latest');
        // Neutral: never the AI accent, and centred over the thread.
        expect(button.className).not.toMatch(/lg-ai|btn-accent/);
        const box = button.getBoundingClientRect();
        const column = thread().getBoundingClientRect();
        expect(Math.abs(box.left + box.width / 2 - (column.left + column.width / 2))).toBeLessThanOrEqual(2);
        expect(box.bottom).toBeLessThanOrEqual(document.querySelector('lg-chat-composer')!.getBoundingClientRect().top);

        button.click();
        await settle();
        expect(fromBottom()).toBeLessThanOrEqual(2);
        expect(jump()).toBeNull();

        await send(CHUNKS);
        expect(fromBottom()).toBeLessThanOrEqual(2);
        expect(jump()).toBeNull();
    });

    it('drops the button once the turn ends', async () => {
        for (let i = 0; i < CHUNKS / 2; i++) await send(i);
        thread().scrollTop = 0;
        thread().dispatchEvent(new Event('scroll'));
        await settle();
        expect(jump()).not.toBeNull();

        server.next({event: 'done', data: {state: 'DONE'}});
        await settle();
        expect(jump()).toBeNull();
    });
});

/**
 * The ring says what it stands for (spec 022): beside an answer, what happened to that turn
 * (ISC-474, ISC-475); on the empty chat's plate, the chat's own state (ISC-476). In a real browser,
 * because the popover is in the top layer and the turn is a transition the browser runs.
 */
describe('the status popovers (ISC-474, ISC-475, ISC-476)', () => {
    let fixture: ComponentFixture<App>;
    let http: HttpTestingController;
    let server: Subject<ChatEvent>;

    const T0 = '2026-09-27T08:00:00Z';
    const at = (seconds: number) => new Date(Date.parse(T0) + seconds * 1000).toISOString();
    const step = (ordinal: number, state: 'RUNNING' | 'DONE' = 'DONE') =>
        ({ordinal, tool: 'search_offers', label: 'Searching the shortlist', state, count: 3, durationMs: 400}) as const;
    const stored = (id: number, state: string, extra: Record<string, unknown> = {}) => ({
        id,
        question: `Question ${id}?`,
        answer: `Answer ${id}.`,
        state,
        steps: [step(1), step(2)],
        sources: [],
        replacesTurnId: null,
        model: 'chat-model',
        createdAt: T0,
        finishedAt: at(4.2),
        endReason: null,
        ...extra,
    });

    beforeEach(async () => {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{path: 'dashboard', component: Screen}])],
        });
        http = TestBed.inject(HttpTestingController);
        server = new Subject<ChatEvent>();
        vi.spyOn(TestBed.inject(ChatApi), 'ask').mockReturnValue(server);
        await page.viewport(1280, 800);
        fixture = TestBed.createComponent(App);
        document.body.appendChild(fixture.nativeElement);
    });

    afterEach(async () => {
        server.complete();
        await reducedMotion(false);
        (fixture.nativeElement as HTMLElement).remove();
    });

    async function settle(): Promise<void> {
        for (let i = 0; i < 4; i++) {
            await new Promise((resolve) => setTimeout(resolve, 0));
            fixture.detectChanges();
        }
        await new Promise((resolve) => requestAnimationFrame(resolve));
    }

    async function openConversation(turns: readonly unknown[]): Promise<void> {
        await TestBed.inject(Router).navigateByUrl('/dashboard?chat=9');
        await settle();
        http.match((r) => r.url === '/api/v1/chat/capability').forEach((r) => r.flush({present: true}));
        await settle();
        http.expectOne('/api/v1/chat/conversations/9').flush({id: 9, title: 'Status', pinnedOfferId: null, turns, updatedAt: T0});
        await settle();
    }

    const glyphs = () => [...document.querySelectorAll<HTMLButtonElement>('.lg-chat-glyph .lg-chat-status-trigger')];
    const openPanel = () => document.querySelector<HTMLElement>('.lg-chat-status-panel:popover-open');

    /** Hover past the intent delay, as a resting pointer does. */
    async function hover(trigger: HTMLElement): Promise<void> {
        trigger.dispatchEvent(new PointerEvent('pointerenter'));
        await new Promise((resolve) => setTimeout(resolve, 200));
        await settle();
    }

    async function leave(trigger: HTMLElement): Promise<void> {
        trigger.dispatchEvent(new PointerEvent('pointerleave'));
        await new Promise((resolve) => setTimeout(resolve, 200));
        await settle();
    }

    function reducedMotion(on: boolean): Promise<unknown> {
        const session = cdp() as unknown as {send(method: string, params: {features: readonly {name: string; value: string}[]}): Promise<unknown>};
        return session.send('Emulation.setEmulatedMedia', {features: on ? [{name: 'prefers-reduced-motion', value: 'reduce'}] : []});
    }

    it('names each stored ending in its popover, with the reason, steps, duration and model', async () => {
        await openConversation([
            stored(1, 'DONE'),
            stored(2, 'INCOMPLETE', {endReason: 'MODEL'}),
            stored(3, 'INCOMPLETE', {endReason: 'BUDGET'}),
            stored(4, 'INCOMPLETE', {endReason: 'ROUNDS'}),
            stored(5, 'INCOMPLETE', {endReason: null}),
            stored(6, 'STOPPED'),
        ]);
        const expected = [
            ['Done', null],
            ['Incomplete', 'The model stopped before it finished.'],
            ['Incomplete', "Today's model calls are spent."],
            ['Incomplete', 'It reached the limit of tool rounds.'],
            ['Incomplete', 'No reason was recorded.'],
            ['Stopped', null],
        ] as const;
        expect(glyphs()).toHaveLength(expected.length);

        for (const [i, [state, reason]] of expected.entries()) {
            const trigger = glyphs()[i];
            await hover(trigger);
            const panel = openPanel();
            expect(panel, `popover ${i + 1} open`).not.toBeNull();
            expect(panel!.querySelector('.lg-chat-status-state')!.textContent!.trim()).toBe(state);
            expect(panel!.querySelector('.lg-chat-status-reason')?.textContent?.trim() ?? null).toBe(reason);
            expect(panel!.querySelector('.lg-chat-status-steps')!.textContent!.trim()).toBe('2');
            expect(panel!.querySelector('.lg-chat-status-duration')!.textContent!.trim()).toBe('4.2 s');
            expect(panel!.querySelector('.lg-chat-status-model')!.textContent!.trim()).toBe('chat-model');
            // Below or above the ring, never over it (ISC-524 placed it through the picker's helper).
            const box = panel!.getBoundingClientRect();
            const ring = trigger.getBoundingClientRect();
            expect(box.top >= ring.bottom || box.bottom <= ring.top, 'never over the ring').toBe(true);
            await leave(trigger);
            expect(openPanel(), `popover ${i + 1} closed after leaving`).toBeNull();
        }
    });

    it("names an answer's tokens and its cost: an amount, free, price unknown, and neither on a turn stored before them (ISC-525)", async () => {
        await openConversation([
            stored(1, 'DONE', {model: 'model-x', connection: 'SECOND', inputTokens: 1840, outputTokens: 212, costUsd: '0.008806'}),
            stored(2, 'DONE', {model: 'model-a', connection: 'PRIMARY', inputTokens: 1200, outputTokens: 80, costUsd: '0'}),
            stored(3, 'DONE', {model: 'model-y', connection: 'SECOND', inputTokens: 900, outputTokens: 40}),
            stored(4, 'DONE', {model: 'model-a', connection: null}),
        ]);
        http.match((r) => r.url === '/api/v1/chat/models').forEach((r) => r.flush(RECORDED_MODELS));
        await settle();
        const expected = [
            ['1,840 in · 212 out', '$0.0088'],
            ['1,200 in · 80 out', 'free'],
            ['900 in · 40 out', 'price unknown'],
            [null, null],
        ] as const;
        expect(glyphs()).toHaveLength(expected.length);

        for (const [i, [tokens, cost]] of expected.entries()) {
            const trigger = glyphs()[i];
            await hover(trigger);
            const panel = openPanel();
            expect(panel, `popover ${i + 1} open`).not.toBeNull();
            const tokensRow = panel!.querySelector('.lg-chat-status-tokens');
            const costRow = panel!.querySelector('.lg-chat-status-cost');
            expect(tokensRow?.textContent?.trim() ?? null, `turn ${i + 1}'s tokens`).toBe(tokens);
            expect(costRow?.textContent?.trim() ?? null, `turn ${i + 1}'s cost`).toBe(cost);
            if (tokensRow !== null) expect(tokensRow.previousElementSibling!.textContent!.trim()).toBe('Tokens');
            if (costRow !== null) expect(costRow.previousElementSibling!.textContent!.trim()).toBe('Cost');
            await leave(trigger);
        }
    });

    it("carries the done event's tokens into the live answer's popover (ISC-525)", async () => {
        await openConversation([]);
        http.match((r) => r.url === '/api/v1/chat/models').forEach((r) => r.flush(RECORDED_MODELS));
        const input = document.querySelector<HTMLTextAreaElement>('.lg-chat-input')!;
        input.value = 'What pays?';
        input.dispatchEvent(new Event('input'));
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true}));
        await settle();
        server.next({event: 'turn', data: {turnId: 11, model: 'model-a', connection: 'PRIMARY'}});
        server.next({event: 'text', data: {delta: 'Two.'}});
        server.next({event: 'done', data: {state: 'DONE', inputTokens: 1840, outputTokens: 212, costUsd: '0'}});
        await settle();

        const trigger = glyphs().at(-1)!;
        await hover(trigger);
        const panel = openPanel()!;
        expect(panel.querySelector('.lg-chat-status-tokens')?.textContent?.trim()).toBe('1,840 in · 212 out');
        expect(panel.querySelector('.lg-chat-status-cost')?.textContent?.trim()).toBe('free');
    });

    it("carries a failed turn's tokens and cost from its error event into the live answer's popover (ISC-525)", async () => {
        await openConversation([]);
        http.match((r) => r.url === '/api/v1/chat/models').forEach((r) => r.flush(RECORDED_MODELS));
        const input = document.querySelector<HTMLTextAreaElement>('.lg-chat-input')!;
        input.value = 'What pays?';
        input.dispatchEvent(new Event('input'));
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true}));
        await settle();
        server.next({event: 'turn', data: {turnId: 12, model: 'model-y', connection: 'SECOND'}});
        server.next({event: 'text', data: {delta: 'Half'}});
        server.next({event: 'error', data: {reason: 'MODEL', message: 'x', inputTokens: 900, outputTokens: 40, costUsd: '0.0012'}});
        await settle();

        const trigger = glyphs().at(-1)!;
        await hover(trigger);
        const panel = openPanel()!;
        expect(panel.querySelector('.lg-chat-status-tokens')?.textContent?.trim()).toBe('900 in · 40 out');
        expect(panel.querySelector('.lg-chat-status-cost')?.textContent?.trim()).toBe('$0.0012');
    });

    it('says usage not reported, and no cost, on a billed turn whose provider reported no usage (ISC-525)', async () => {
        await openConversation([stored(1, 'DONE', {model: 'model-y', connection: 'SECOND', inputTokens: 0, outputTokens: 0})]);
        http.match((r) => r.url === '/api/v1/chat/models').forEach((r) => r.flush(RECORDED_MODELS));
        await settle();
        await hover(glyphs()[0]);
        const panel = openPanel()!;
        expect(panel.querySelector('.lg-chat-status-tokens')?.textContent?.trim()).toBe('usage not reported');
        expect(panel.querySelector('.lg-chat-status-tokens')!.previousElementSibling!.textContent!.trim()).toBe('Tokens');
        expect(panel.querySelector('.lg-chat-status-cost')).toBeNull();
    });

    it('says usage not fully reported, never price unknown, on a billed turn the server marked usageIncomplete, stored or live (spec 026)', async () => {
        await openConversation([
            stored(1, 'STOPPED', {model: 'model-x', connection: 'SECOND', inputTokens: 1000, outputTokens: 100, usageIncomplete: true}),
            stored(2, 'STOPPED', {model: 'model-x', connection: 'SECOND', inputTokens: 0, outputTokens: 0, usageIncomplete: true}),
            stored(3, 'DONE', {model: 'model-y', connection: 'SECOND', inputTokens: 900, outputTokens: 40}),
        ]);
        http.match((r) => r.url === '/api/v1/chat/models').forEach((r) => r.flush(RECORDED_MODELS));
        await settle();
        const input = document.querySelector<HTMLTextAreaElement>('.lg-chat-input')!;
        input.value = 'What pays?';
        input.dispatchEvent(new Event('input'));
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true}));
        await settle();
        server.next({event: 'turn', data: {turnId: 13, model: 'model-x', connection: 'SECOND'}});
        server.next({event: 'text', data: {delta: 'Half'}});
        server.next({event: 'error', data: {reason: 'MODEL', message: 'x', inputTokens: 700, outputTokens: 30, usageIncomplete: true}});
        await settle();
        const expected = [
            ['1,000 in · 100 out', 'usage not fully reported'],
            ['usage not reported', null],
            ['900 in · 40 out', 'price unknown'],
            ['700 in · 30 out', 'usage not fully reported'],
        ] as const;
        expect(glyphs()).toHaveLength(expected.length);

        for (const [i, [tokens, cost]] of expected.entries()) {
            const trigger = glyphs()[i];
            await hover(trigger);
            const panel = openPanel();
            expect(panel, `popover ${i + 1} open`).not.toBeNull();
            expect(panel!.querySelector('.lg-chat-status-tokens')?.textContent?.trim() ?? null, `turn ${i + 1}'s tokens`).toBe(tokens);
            const costRow = panel!.querySelector('.lg-chat-status-cost');
            expect(costRow?.textContent?.trim() ?? null, `turn ${i + 1}'s cost`).toBe(cost);
            if (costRow !== null) expect(costRow.previousElementSibling!.textContent!.trim()).toBe('Cost');
            await leave(trigger);
        }
    });

    it('says price unknown on a billed turn without a cost while the models have not answered, never free (ISC-525)', async () => {
        await openConversation([stored(1, 'DONE', {model: 'model-y', connection: 'SECOND', inputTokens: 900, outputTokens: 40})]);
        // `/models` stays unanswered: the cost is read off the turn alone.
        await hover(glyphs()[0]);
        const panel = openPanel()!;
        expect(panel.querySelector('.lg-chat-status-tokens')?.textContent?.trim()).toBe('900 in · 40 out');
        expect(panel.querySelector('.lg-chat-status-cost')?.textContent?.trim()).toBe('price unknown');
    });

    it('says free only for a zero cost on an unbilled connection; on a billed one, or before the models answer, the amount (spec 026)', async () => {
        const turns = [
            stored(1, 'DONE', {model: 'model-x', connection: 'SECOND', inputTokens: 10, outputTokens: 2, costUsd: '0'}),
            stored(2, 'DONE', {model: 'model-a', connection: 'PRIMARY', inputTokens: 10, outputTokens: 2, costUsd: '0'}),
            stored(3, 'DONE', {model: 'model-a', connection: null, inputTokens: 10, outputTokens: 2, costUsd: '0'}),
        ];
        await openConversation(turns);
        const costs = async (): Promise<(string | null)[]> => {
            const seen: (string | null)[] = [];
            for (const trigger of glyphs()) {
                await hover(trigger);
                seen.push(openPanel()?.querySelector('.lg-chat-status-cost')?.textContent?.trim() ?? null);
                await leave(trigger);
            }
            return seen;
        };
        // `/models` unanswered: nothing is known to be free, so each zero is the amount.
        expect(await costs(), 'before the models answer').toEqual(['$0.00', '$0.00', '$0.00']);
        http.match((r) => r.url === '/api/v1/chat/models').forEach((r) => r.flush(RECORDED_MODELS));
        await settle();
        // The second connection is billed; the primary, and an old turn without a connection (the primary), are not.
        expect(await costs(), 'after the models answer').toEqual(['$0.00', 'free', 'free']);
    });

    it('says working while a tool runs and writing while the text streams, and turns the mark while open', async () => {
        await openConversation([]);
        const input = document.querySelector<HTMLTextAreaElement>('.lg-chat-input')!;
        input.value = 'What pays?';
        input.dispatchEvent(new Event('input'));
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true}));
        await settle();
        server.next({event: 'turn', data: {turnId: 31, model: 'chat-model'}});
        server.next({event: 'step', data: step(1, 'RUNNING')});
        await settle();

        const trigger = glyphs().at(-1)!;
        await hover(trigger);
        expect(openPanel()!.querySelector('.lg-chat-status-state')!.textContent!.trim()).toBe('Working: a tool is running');
        // The live turn names its model as the stored one does; it used to appear only after a reload.
        expect(openPanel()!.querySelector('.lg-chat-status-model')?.textContent?.trim()).toBe('chat-model');
        const mark = trigger.querySelector('svg.mark')!;
        expect(mark.getAnimations().length, 'the turn plays while the popover is open').toBeGreaterThan(0);

        server.next({event: 'step', data: step(1)});
        server.next({event: 'text', data: {delta: 'Most '}});
        await settle();
        expect(openPanel()!.querySelector('.lg-chat-status-state')!.textContent!.trim()).toBe('Writing the answer');

        document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}));
        await settle();
        expect(openPanel()).toBeNull();
    });

    it('closes only its popover on Escape from the ring, never the drawer around it', async () => {
        await openConversation([stored(1, 'DONE')]);
        const trigger = glyphs()[0];
        trigger.focus();
        await settle();
        expect(openPanel(), 'open on focus').not.toBeNull();

        trigger.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true, cancelable: true}));
        await settle();
        expect(openPanel()).toBeNull();
        expect(document.querySelector('.lg-chat-drawer'), 'the drawer stays open').not.toBeNull();
    });

    it('opens on a tap and closes on a tap elsewhere, as a touch screen needs', async () => {
        // A tap brings pointerenter and pointerleave around it and, on iOS, no focus: hover and focus
        // alone opened nothing on a phone.
        await openConversation([stored(1, 'DONE')]);
        const trigger = glyphs()[0];
        const touch = (type: string, target: EventTarget) =>
            target.dispatchEvent(new PointerEvent(type, {pointerType: 'touch', bubbles: true}));
        touch('pointerenter', trigger);
        touch('pointerdown', trigger);
        touch('pointerup', trigger);
        trigger.click();
        touch('pointerleave', trigger);
        await new Promise((resolve) => setTimeout(resolve, 200));
        await settle();
        expect(openPanel(), 'open after the tap').not.toBeNull();

        touch('pointerdown', document.body);
        await settle();
        expect(openPanel(), 'closed by a tap elsewhere').toBeNull();
    });

    it('stays open after a tap where the browser also focuses the ring, as Chromium on Android does', async () => {
        await openConversation([stored(1, 'DONE')]);
        const trigger = glyphs()[0];
        trigger.dispatchEvent(new PointerEvent('pointerdown', {pointerType: 'touch', bubbles: true}));
        trigger.focus();
        trigger.click();
        await settle();
        expect(openPanel(), 'the focus and the tap must not cancel each other out').not.toBeNull();

        // A later keyboard focus still opens it: the tap is not remembered past its own click.
        trigger.blur();
        await settle();
        trigger.focus();
        await settle();
        expect(openPanel(), 'open on keyboard focus after a tap').not.toBeNull();
    });

    it('closes a hovered popover on Escape without taking the key from where focus is', async () => {
        await openConversation([stored(1, 'DONE')]);
        await hover(glyphs()[0]);
        expect(openPanel()).not.toBeNull();
        const input = document.querySelector<HTMLTextAreaElement>('.lg-chat-input')!;
        input.focus();
        let reached = false;
        const onward = () => (reached = true);
        document.addEventListener('keydown', onward);
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true, cancelable: true}));
        document.removeEventListener('keydown', onward);
        await settle();
        expect(openPanel()).toBeNull();
        expect(reached, 'the key still reached whatever handles it where focus is').toBe(true);
    });

    it('opens on keyboard focus with a name that states the turn, and does not turn under reduced motion', async () => {
        await openConversation([stored(1, 'INCOMPLETE', {endReason: 'ROUNDS'})]);
        const trigger = glyphs()[0];
        expect(trigger.getAttribute('aria-label')).toBe('Answer status: Incomplete');
        expect(trigger.getAttribute('aria-describedby')).toBe(document.querySelector('.lg-chat-status-panel')!.id);

        await reducedMotion(true);
        trigger.focus();
        await settle();
        expect(openPanel(), 'open on focus').not.toBeNull();
        await new Promise((resolve) => setTimeout(resolve, 50));
        expect(trigger.querySelector('svg.mark')!.getAnimations()).toHaveLength(0);

        trigger.blur();
        await settle();
        expect(openPanel()).toBeNull();
    });

    it("shows the chat's own status on the empty chat's plate, asked when it opens", async () => {
        await TestBed.inject(Router).navigateByUrl('/dashboard?chat=new');
        await settle();
        http.match((r) => r.url === '/api/v1/chat/capability').forEach((r) => r.flush({present: true}));
        await settle();
        http.match(() => true).forEach((r) => r.flush([]));
        await settle();

        const plate = document.querySelector<HTMLButtonElement>('.lg-chat-plate .lg-chat-status-trigger')!;
        expect(plate.getAttribute('aria-label')).toBe('Chat status');
        expect(http.match('/api/v1/chat/status'), 'nothing asked before it opens').toHaveLength(0);
        await hover(plate);
        http.expectOne('/api/v1/chat/status')
            .flush({model: 'chat-model', callsUsed: 12, callsLimit: 200, toolRounds: 6, secondCalls: null, secondLimit: null});
        await settle();
        const panel = openPanel()!;
        expect(panel.querySelector('.lg-chat-status-model')!.textContent!.trim()).toBe('chat-model');
        expect(panel.querySelector('.lg-chat-status-calls')!.textContent!.trim()).toBe('12 of 200');
        expect(panel.querySelector('.lg-chat-status-rounds')!.textContent!.trim()).toBe('6');
        expect(panel.querySelector('.lg-chat-status-second-calls'), 'no second connection, no second counter').toBeNull();
    });

    it("names the second connection's counter beside the chat's when there is one (ISC-510)", async () => {
        await TestBed.inject(Router).navigateByUrl('/dashboard?chat=new');
        await settle();
        http.match((r) => r.url === '/api/v1/chat/capability').forEach((r) => r.flush({present: true}));
        await settle();
        http.match(() => true).forEach((r) => r.flush([]));
        await settle();

        await hover(document.querySelector<HTMLButtonElement>('.lg-chat-plate .lg-chat-status-trigger')!);
        http.expectOne('/api/v1/chat/status')
            .flush({model: 'chat-model', callsUsed: 12, callsLimit: 200, toolRounds: 6, secondCalls: 3, secondLimit: 20});
        await settle();
        const panel = openPanel()!;
        expect(panel.querySelector('.lg-chat-status-calls')!.textContent!.trim()).toBe('12 of 200');
        const second = panel.querySelector('.lg-chat-status-second-calls')!;
        expect(second, 'the second counter').not.toBeNull();
        expect(second.textContent!.trim()).toBe('3 of 20');
        expect(second.previousElementSibling!.textContent!.trim()).toBe('Calls on the second connection today');
    });

    it('keeps the numbers it has when a refresh fails, with a note beside them, and says failed only with none', async () => {
        await TestBed.inject(Router).navigateByUrl('/dashboard?chat=new');
        await settle();
        http.match((r) => r.url === '/api/v1/chat/capability').forEach((r) => r.flush({present: true}));
        await settle();
        http.match(() => true).forEach((r) => r.flush([]));
        await settle();
        const plate = () => document.querySelector<HTMLButtonElement>('.lg-chat-plate .lg-chat-status-trigger')!;

        // The first read fails: no status at all, so the bare failed state.
        await hover(plate());
        http.expectOne('/api/v1/chat/status').flush('down', {status: 503, statusText: 'Service Unavailable'});
        await settle();
        expect(openPanel()!.textContent).toContain('The status did not load.');
        expect(openPanel()!.querySelector('.lg-chat-status-calls')).toBeNull();
        await leave(plate());

        // The next read answers: the numbers, no note.
        await hover(plate());
        http.expectOne('/api/v1/chat/status')
            .flush({model: 'chat-model', callsUsed: 12, callsLimit: 200, toolRounds: 6, secondCalls: 3, secondLimit: 20});
        await settle();
        expect(openPanel()!.querySelector('.lg-chat-status-calls')!.textContent!.trim()).toBe('12 of 200');
        expect(openPanel()!.querySelector('.lg-chat-status-stale')).toBeNull();
        await leave(plate());

        // A refresh fails over a kept status: the numbers stay, the note says the refresh failed.
        await hover(plate());
        http.expectOne('/api/v1/chat/status').flush('down', {status: 503, statusText: 'Service Unavailable'});
        await settle();
        const panel = openPanel()!;
        expect(panel.querySelector('.lg-chat-status-calls')!.textContent!.trim(), 'the kept numbers').toBe('12 of 200');
        expect(panel.querySelector('.lg-chat-status-second-calls')!.textContent!.trim()).toBe('3 of 20');
        expect(panel.querySelector('.lg-chat-status-stale')?.textContent?.trim(), 'the note beside them').toBe(
            'The last refresh failed; these numbers may be out of date.',
        );
        expect(panel.textContent).not.toContain('The status did not load.');
    });

    it('stays inside a 375px viewport with a 1rem margin, and moves with its ring while the thread scrolls (ISC-524)', async () => {
        await page.viewport(375, 700);
        const long = 'A long answer that wraps over several lines on a phone. '.repeat(12);
        await openConversation([1, 2, 3, 4, 5, 6].map((id) => stored(id, 'DONE', {answer: long})));
        const thread = document.querySelector<HTMLElement>('.lg-chat-thread')!;
        expect(thread.scrollHeight, 'the thread scrolls').toBeGreaterThan(thread.clientHeight + 200);

        // The ring at the end of its row, so a panel beside it would run past the right edge.
        const trigger = glyphs()[3];
        trigger.closest<HTMLElement>('.lg-chat-answer')!.style.flexDirection = 'row-reverse';
        // The ring halfway down the thread, so a scroll of 40px leaves it in view on either side.
        const middle = () => {
            const box = thread.getBoundingClientRect();
            return box.top + box.height / 2;
        };
        thread.scrollTop += trigger.getBoundingClientRect().top - middle();
        await settle();
        expect(trigger.getBoundingClientRect().right, 'the ring sits near the right edge').toBeGreaterThan(375 - 64);

        await hover(trigger);
        const panel = openPanel()!;
        expect(panel, 'open').not.toBeNull();
        const margin = parseFloat(getComputedStyle(document.documentElement).fontSize);
        const inside = () => {
            const box = panel.getBoundingClientRect();
            expect(box.left, 'left margin').toBeGreaterThanOrEqual(margin - 0.5);
            expect(box.right, 'right margin').toBeLessThanOrEqual(375 - margin + 0.5);
            expect(box.top, 'top margin').toBeGreaterThanOrEqual(margin - 0.5);
            expect(box.bottom, 'bottom margin').toBeLessThanOrEqual(window.innerHeight - margin + 0.5);
        };
        inside();
        // Not over the ring.
        const ring = trigger.getBoundingClientRect();
        const box = panel.getBoundingClientRect();
        expect(box.top >= ring.bottom || box.bottom <= ring.top, 'above or below the ring, never over it').toBe(true);

        const offset = box.top - ring.top;
        const ringTop = ring.top;
        thread.scrollTop += 40;
        await settle();
        await new Promise((resolve) => requestAnimationFrame(resolve));
        expect(trigger.getBoundingClientRect().top, 'the ring moved').toBeCloseTo(ringTop - 40, 0);
        expect(openPanel(), 'still open').not.toBeNull();
        expect(panel.getBoundingClientRect().top - trigger.getBoundingClientRect().top, 'the panel moved with it').toBeCloseTo(offset, 0);
        inside();
    });

    it('chooses its side by the height it has, never by the room an earlier open left on it (ISC-524)', async () => {
        await openConversation([stored(1, 'DONE')]);
        const trigger = glyphs()[0];
        const margin = parseFloat(getComputedStyle(document.documentElement).fontSize);
        /** The ring pinned at a viewport y, whatever box contains it, so each open sees exactly the room it is given. */
        const pin = async (top: number): Promise<void> => {
            // A resize moves the drawer around the ring a little later: every finite animation done first.
            const finite = document.getAnimations().filter((a) => Number.isFinite(Number(a.effect?.getComputedTiming().endTime)));
            await Promise.all(finite.map((a) => a.finished.catch(() => undefined)));
            await new Promise((resolve) => setTimeout(resolve, 250));
            await settle();
            Object.assign(trigger.style, {position: 'fixed', left: '200px', top: `${top}px`});
            trigger.style.top = `${2 * top - trigger.getBoundingClientRect().top}px`;
            await settle();
            expect(trigger.getBoundingClientRect().top, 'the ring pinned').toBeCloseTo(top, 0);
        };

        // Its real height, opened with room on every side.
        await pin(300);
        await hover(trigger);
        const height = openPanel()!.getBoundingClientRect().height;
        await leave(trigger);
        const ring = trigger.getBoundingClientRect().height;

        // A short viewport, more room above than below and too little for the panel on either side: above, clipped.
        const low = Math.round(height * 0.6) + margin + 8;
        await page.viewport(1280, 2 * low);
        await pin(low);
        await hover(trigger);
        const clipped = openPanel()!.getBoundingClientRect();
        expect(clipped.bottom, 'above the ring').toBeLessThanOrEqual(trigger.getBoundingClientRect().top + 0.5);
        expect(clipped.height, 'clipped to the room above').toBeLessThan(height - 8);
        await leave(trigger);

        // Room below for the clipped height but not for the real one, and more room above: above it goes.
        await page.viewport(1280, 800);
        const below = Math.round((clipped.height + height) / 2) + 32;
        await pin(800 - below - ring);
        await hover(trigger);
        const box = openPanel()!.getBoundingClientRect();
        expect(box.bottom, 'above, chosen by its real height').toBeLessThanOrEqual(trigger.getBoundingClientRect().top + 0.5);
        expect(box.height, 'its whole height').toBeCloseTo(height, 0);
    });
});
