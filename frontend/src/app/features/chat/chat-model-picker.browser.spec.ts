import {Component} from '@angular/core';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {page, userEvent} from 'vitest/browser';
import {Subject} from 'rxjs';
import {Dispatcher} from '@ngrx/signals/events';
import {ChatApi} from '@core/api/chat.api';
import {chatEvents} from '@core/store/chat.events';
import {RECORDED_MODELS, RECORDED_STATUS} from '@core/api/chat-stub';
import {ChatEvent, ChatModels, ChatStatus} from '@core/model/chat';
import {composite, contrastRatio, Rgb} from '@core/theme/color-math';
import {App} from '../../app';

@Component({template: '<h1>screen</h1>'})
class Screen {}

/** The words a box offers a reader: its text with runs of white space collapsed. */
function words(element: Element | null | undefined): string {
    return (element?.textContent ?? '').replace(/\s+/g, ' ').trim();
}

/** The recorded status with the billed second connection's day spent. */
const BILLED_SPENT: ChatStatus = {...RECORDED_STATUS, secondCalls: RECORDED_STATUS.secondLimit};

/**
 * One billed connection and no second: the recorded billed models as the primary, none flagged as the default,
 * so the default is a model it does not list — the server flags a listed model of the default's name.
 */
const PRIMARY_ONLY: ChatModels = {connections: [{...RECORDED_MODELS.connections[1], name: 'PRIMARY'}], defaultModelName: 'model-z'};

/** The status on that catalog: no second connection's ceiling, the primary's spend. */
const PRIMARY_ONLY_STATUS: ChatStatus = {
    ...RECORDED_STATUS,
    secondCalls: null,
    secondLimit: null,
    spend: {connections: [{name: 'PRIMARY', today: '0.0042', month: '0.12', unpricedToday: 0, unpricedMonth: 0}], today: '0.0042', month: '0.12'},
};

/** Any CSS colour the browser understands, as sRGB and alpha, through a 1×1 canvas. */
function colour(css: string): Rgb & {alpha: number} {
    const canvas = document.createElement('canvas');
    canvas.width = 1;
    canvas.height = 1;
    const ctx = canvas.getContext('2d', {willReadFrequently: true})!;
    ctx.clearRect(0, 0, 1, 1);
    ctx.fillStyle = css;
    ctx.fillRect(0, 0, 1, 1);
    const [r, g, b, a] = ctx.getImageData(0, 0, 1, 1).data;
    return {r, g, b, alpha: a / 255};
}

/** The ground a box's text is painted on: every background from the first opaque one up, composited down. */
function ground(element: Element): Rgb {
    const layers: (Rgb & {alpha: number})[] = [];
    for (let e: Element | null = element; e !== null; e = e.parentElement) {
        const layer = colour(getComputedStyle(e).backgroundColor);
        if (layer.alpha > 0) layers.push(layer);
        if (layer.alpha >= 1) break;
    }
    return layers.reverse().reduce<Rgb>((below, layer) => composite(layer, layer.alpha, below), {r: 255, g: 255, b: 255});
}

/** Every box in `root` that paints text of its own, visible, and not a screen reader's alone. */
function textBoxes(root: Element): HTMLElement[] {
    return Array.from(root.querySelectorAll<HTMLElement>('*')).filter(
        (e) =>
            !e.closest('.sr-only') &&
            e.getClientRects().length > 0 &&
            Array.from(e.childNodes).some((n) => n.nodeType === Node.TEXT_NODE && (n.textContent ?? '').trim() !== ''),
    );
}

/**
 * The model picker beside the composer (ISC-522, ISC-523, ISC-524): a button naming the pick in place of
 * the native select, opening a popover with one group per connection — its icon, local or billed, its
 * kind, its calls today and its spend — each model with its price, "free" or "price unknown", one default
 * badge, one check on the current pick, and the total spend at the foot.
 *
 * <p>In a real browser, because the popover is the browser's own top layer, the keys and the outside
 * click are real gestures, and the full app's drawer — docked or the modal sheet — is what opens it. The
 * catalog and the status are the recorded stubs: an unbilled connection with two free models, a billed
 * one with a priced and an unpriced model, and a status with spend.
 */
describe('the chat model picker (ISC-522)', () => {
    let fixture: ComponentFixture<App>;
    let http: HttpTestingController;
    let server: Subject<ChatEvent>;
    let ask: ReturnType<typeof vi.spyOn>;
    let theme: string | null;

    async function settle(): Promise<void> {
        for (let i = 0; i < 4; i++) {
            await new Promise((resolve) => setTimeout(resolve, 0));
            fixture.detectChanges();
        }
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

    /** Every running animation — the docked drawer slides in — finished, and two frames for the re-place. */
    async function frames(): Promise<void> {
        const finite = document.getAnimations().filter((a) => Number.isFinite(Number(a.effect?.getComputedTiming().endTime)));
        await Promise.all(finite.map((a) => a.finished.catch(() => undefined)));
        for (let i = 0; i < 2; i++) await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
        await settle();
        for (let i = 0; i < 2; i++) await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
    }

    /** The app at one width, the drawer open on conversation 9, the recorded catalog and the given status. */
    async function boot(width = 1280, status: ChatStatus = RECORDED_STATUS, height = 800, models: ChatModels = RECORDED_MODELS): Promise<void> {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{path: 'dashboard', component: Screen}])],
        });
        http = TestBed.inject(HttpTestingController);
        server = new Subject<ChatEvent>();
        ask = vi.spyOn(TestBed.inject(ChatApi), 'ask').mockReturnValue(server);
        await page.viewport(width, height);
        fixture = TestBed.createComponent(App);
        document.body.appendChild(fixture.nativeElement);
        await TestBed.inject(Router).navigateByUrl('/dashboard?chat=9');
        await settle();
        http.match((r) => r.url === '/api/v1/chat/capability').forEach((r) => r.flush({present: true}));
        await settle();
        http.expectOne('/api/v1/chat/conversations/9').flush({id: 9, title: 'Rates', pinnedOfferId: null, turns: [], updatedAt: '2026-10-12T08:00:00Z'});
        await settle();
        await drawerLoaded();
        http.expectOne('/api/v1/chat/models').flush(models);
        await settle();
        const asked = http.match('/api/v1/chat/status');
        expect(asked, 'the status asked with the catalog').toHaveLength(1);
        asked[0].flush(status);
        await settle();
    }

    beforeEach(() => {
        theme = document.documentElement.getAttribute('data-theme');
    });

    afterEach(() => {
        server.complete();
        (fixture.nativeElement as HTMLElement).remove();
        if (theme === null) document.documentElement.removeAttribute('data-theme');
        else document.documentElement.setAttribute('data-theme', theme);
    });

    const composer = () => document.querySelector('lg-chat-composer')!;
    const trigger = () => composer().querySelector<HTMLButtonElement>('button.lg-chat-model-trigger');
    const panel = () => composer().querySelector<HTMLElement>('.lg-chat-model-panel');
    const groups = () => Array.from(panel()!.querySelectorAll<HTMLElement>('[role="group"]'));
    const rows = (group: Element) => Array.from(group.querySelectorAll<HTMLElement>('[role="option"]'));
    const row = (name: string) => Array.from(panel()!.querySelectorAll<HTMLElement>('[role="option"]')).find((o) => o.querySelector('.lg-chat-model-name')?.textContent?.trim() === name)!;
    const focused = () => (document.activeElement as HTMLElement | null)?.querySelector('.lg-chat-model-name')?.textContent?.trim() ?? null;
    const isOpen = () => panel()!.matches(':popover-open');

    /** An icon is named when it carries a label itself, or is hidden beside words that say the same. */
    function named(svg: SVGElement): boolean {
        if (svg.getAttribute('role') === 'img' && (svg.getAttribute('aria-label') ?? '').trim() !== '') return true;
        if (svg.getAttribute('aria-hidden') !== 'true') return false;
        const button = svg.closest('button');
        if (button !== null && (button.getAttribute('aria-label') ?? '').trim() !== '') return true;
        return words(svg.closest('lg-icon')?.parentElement) !== '';
    }

    async function openPicker(): Promise<void> {
        trigger()!.click();
        await settle();
    }

    async function press(key: string): Promise<void> {
        await userEvent.keyboard(`{${key}}`);
        await settle();
    }

    async function askQuestion(question: string): Promise<void> {
        const input = document.querySelector<HTMLTextAreaElement>('.lg-chat-input')!;
        input.value = question;
        input.dispatchEvent(new Event('input'));
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true}));
        await settle();
    }

    it('replaces the select with a ghost button naming the pick, its connection local', async () => {
        await boot();
        expect(composer(), 'the drawer is open').not.toBeNull();
        expect(composer().querySelector('select'), 'no native select in the composer').toBeNull();
        expect(composer().querySelector('.lg-chat-model-note'), 'the note under the composer is gone').toBeNull();

        const button = trigger();
        expect(button, 'a button names the pick').not.toBeNull();
        expect(button!.classList.contains('btn-ghost')).toBe(true);
        expect(button!.getAttribute('aria-label')).toBe('Model: model-a, local');
        expect(button!.getAttribute('aria-haspopup')).toBe('listbox');
        expect(button!.getAttribute('aria-expanded')).toBe('false');
        expect(words(button!.querySelector('.lg-chat-model-trigger-name'))).toBe('model-a');
        expect(Array.from(button!.querySelectorAll('svg')).every(named), 'every trigger icon named').toBe(true);
    });

    it('opens one group per connection with its icon, kind, calls and spend, each model with its price', async () => {
        await boot();
        await openPicker();

        expect(isOpen(), 'the popover opens').toBe(true);
        expect(trigger()!.getAttribute('aria-expanded')).toBe('true');
        expect(words(panel()!.querySelector('.lg-chat-model-units'))).toBe('Prices in USD per 1M tokens · in / out');

        const [local, billed] = groups();
        expect(groups()).toHaveLength(2);

        const localHead = local.querySelector('.lg-chat-model-head')!;
        expect(localHead.querySelector('svg')!.getAttribute('aria-label')).toBe('Local');
        expect(words(localHead.querySelector('.lg-chat-model-kind'))).toBe('local');
        expect(words(localHead.querySelector('.lg-chat-model-calls .sr-only'))).toBe('12 of 200 calls today');
        expect(words(local.querySelector('.lg-chat-model-spend'))).toBe('Today $0.00 · this month $0.00');

        const billedHead = billed.querySelector('.lg-chat-model-head')!;
        expect(billedHead.querySelector('svg')!.getAttribute('aria-label')).toBe('Billed');
        expect(words(billedHead.querySelector('.lg-chat-model-kind'))).toBe('hosted');
        expect(words(billedHead.querySelector('.lg-chat-model-calls .sr-only'))).toBe('4 of 50 calls today');
        expect(words(billed.querySelector('.lg-chat-model-spend'))).toBe('Today $0.0122 · this month $0.348');
        // The turns a missing price left out of a group's sums, on that group only.
        expect(words(billed.querySelector('.lg-chat-model-unpriced'))).toBe('Turns not counted for a missing price: 0 today · 1 this month');
        expect(local.querySelector('.lg-chat-model-unpriced'), 'none on a group without unpriced turns').toBeNull();
        // The turns whose usage the provider did not report, the other cause, on a line of its own.
        expect(words(billed.querySelector('.lg-chat-model-unreported'))).toBe('Turns not counted, usage not reported: 1 today · 2 this month');
        expect(local.querySelector('.lg-chat-model-unreported'), 'none on a group without unreported turns').toBeNull();

        expect(rows(local).map((r) => words(r.querySelector('.lg-chat-model-price')))).toEqual(['free', 'free']);
        expect(rows(billed).map((r) => words(r.querySelector('.lg-chat-model-price')))).toEqual(['$3.00 in · $15.50 out', 'price unknown']);
        expect(row('model-y').querySelector('.lg-chat-model-price svg'), 'price unknown leads with circle-help').not.toBeNull();

        // One default badge, on the default; one check, on the current pick.
        const badges = panel()!.querySelectorAll('.badge');
        expect(badges).toHaveLength(1);
        expect(words(badges[0])).toBe('Default');
        expect(row('model-a').contains(badges[0])).toBe(true);
        const selected = panel()!.querySelectorAll('[role="option"][aria-selected="true"]');
        expect(selected).toHaveLength(1);
        expect(selected[0]).toBe(row('model-a'));
        expect(panel()!.querySelectorAll('.lg-chat-model-check svg')).toHaveLength(1);

        // The total, and the turns its sum leaves out.
        const foot = panel()!.querySelector('.lg-chat-model-foot')!;
        expect(words(foot.querySelector('.lg-chat-model-total'))).toBe('Today $0.0122 · this month $0.348');
        expect(words(foot.querySelector('.lg-chat-model-unpriced'))).toBe('Turns not counted for a missing price: 0 today · 1 this month');
        expect(words(foot.querySelector('.lg-chat-model-unreported'))).toBe('Turns not counted, usage not reported: 1 today · 2 this month');

        const icons = Array.from(panel()!.querySelectorAll('svg'));
        expect(icons.length).toBeGreaterThan(0);
        expect(icons.filter((svg) => !named(svg)).map((svg) => svg.closest('lg-icon')?.parentElement?.className), 'every icon named').toEqual([]);
    });

    it('keeps the spend current with one billed connection and no second: asked with the catalog and after each turn', async () => {
        await boot(1280, PRIMARY_ONLY_STATUS, 800, PRIMARY_ONLY);
        await openPicker();
        expect(groups()).toHaveLength(1);
        expect(words(groups()[0].querySelector('.lg-chat-model-spend')), 'the group spend').toBe('Today $0.0042 · this month $0.12');
        expect(words(panel()!.querySelector('.lg-chat-model-total')), 'the total').toBe('Today $0.0042 · this month $0.12');
        await press('Escape');

        await askQuestion('What pays?');
        server.next({event: 'turn', data: {turnId: 21, model: 'model-x', connection: 'PRIMARY'}});
        server.next({event: 'text', data: {delta: 'Two.'}});
        server.next({event: 'sources', data: {sources: []}});
        server.next({event: 'done', data: {state: 'DONE', inputTokens: 1000, outputTokens: 100, costUsd: '0.00455'}});
        server.complete();
        await settle();

        const asked = http.match('/api/v1/chat/status');
        expect(asked, 'the status asked again after the turn').toHaveLength(1);
        const spent = '0.0087';
        asked[0].flush({
            ...PRIMARY_ONLY_STATUS,
            callsUsed: 13,
            spend: {connections: [{name: 'PRIMARY', today: spent, month: '0.1287', unpricedToday: 0, unpricedMonth: 0}], today: spent, month: '0.1287'},
        });
        await settle();

        await openPicker();
        expect(words(groups()[0].querySelector('.lg-chat-model-spend')), 'the group spend, refreshed').toBe('Today $0.0087 · this month $0.1287');
        expect(words(panel()!.querySelector('.lg-chat-model-total')), 'the total, refreshed').toBe('Today $0.0087 · this month $0.1287');
    });

    it('picks a row on a click, closes and names the new pick, billed', async () => {
        await boot();
        await openPicker();
        row('model-x').click();
        await settle();

        expect(isOpen(), 'the popover closes').toBe(false);
        expect(trigger()!.getAttribute('aria-expanded')).toBe('false');
        expect(trigger()!.getAttribute('aria-label')).toBe('Model: model-x, billed');
        expect(document.activeElement, 'focus back on the trigger').toBe(trigger());
    });

    it('shows a connection the catalog no longer lists but whose spend is in the total, as a head with that spend and no models', async () => {
        const spentElsewhere: ChatStatus = {
            ...PRIMARY_ONLY_STATUS,
            spend: {
                connections: [
                    {name: 'PRIMARY', today: '0.0042', month: '0.12', unpricedToday: 0, unpricedMonth: 0},
                    {name: 'SECOND', today: '0', month: '0.25', unpricedToday: 0, unpricedMonth: 2},
                ],
                today: '0.0042',
                month: '0.37',
            },
        };
        await boot(1280, spentElsewhere, 800, PRIMARY_ONLY);
        await openPicker();

        expect(groups(), 'the listed connection and the one only the spend names').toHaveLength(2);
        const gone = groups()[1];
        expect(words(gone.querySelector('.lg-chat-model-head-line'))).toBe('Second connection · no longer configured');
        expect(words(gone.querySelector('.lg-chat-model-spend')), 'its spend, part of the total').toBe('Today $0.00 · this month $0.25');
        expect(words(gone.querySelector('.lg-chat-model-unpriced'))).toBe('Turns not counted for a missing price: 0 today · 2 this month');
        expect(rows(gone), 'no models to pick').toHaveLength(0);
        expect(gone.querySelector('.lg-chat-model-none'), 'no "No models" either: it is not a connection without models').toBeNull();
        expect(words(panel()!.querySelector('.lg-chat-model-total'))).toBe('Today $0.0042 · this month $0.37');
        expect(Array.from(gone.querySelectorAll('svg')).every(named), 'every icon named').toBe(true);
    });

    // The default row exists only while no listed model is flagged as the default, and the server flags a listed
    // model of the default's name, so the row never names a listed, priced model: on a billed primary it is "price unknown".
    it('prices the separate default row on a billed primary as "price unknown", never blank', async () => {
        await boot(1280, PRIMARY_ONLY_STATUS, 800, PRIMARY_ONLY);
        await openPicker();
        const fallback = panel()!.querySelector<HTMLElement>('[role="option"][data-value="default"]')!;
        expect(words(fallback.querySelector('.lg-chat-model-name'))).toBe('Default · model-z');
        expect(words(fallback.querySelector('.lg-chat-model-price')), 'never blank on a billed connection').toBe('price unknown');
    });

    it("prices the separate default row with the default's own price when the server names one on a billed primary (spec 026)", async () => {
        await boot(1280, PRIMARY_ONLY_STATUS, 800, {...PRIMARY_ONLY, defaultModelPrice: {input: '2.5', output: '10'}});
        await openPicker();
        const fallback = panel()!.querySelector<HTMLElement>('[role="option"][data-value="default"]')!;
        expect(words(fallback.querySelector('.lg-chat-model-name'))).toBe('Default · model-z');
        expect(words(fallback.querySelector('.lg-chat-model-price'))).toBe('$2.50 in · $10.00 out');
    });

    it('prices the separate default row on an unbilled primary as "free" (spec 026)', async () => {
        const [local] = RECORDED_MODELS.connections;
        const unflagged: ChatModels = {
            connections: [{...local, models: local.models.map((m) => ({...m, defaultModel: false}))}],
            defaultModelName: 'model-z',
            defaultModelPrice: null,
        };
        await boot(1280, PRIMARY_ONLY_STATUS, 800, unflagged);
        await openPicker();
        const fallback = panel()!.querySelector<HTMLElement>('[role="option"][data-value="default"]')!;
        expect(words(fallback.querySelector('.lg-chat-model-price'))).toBe('free');
    });

    it('writes a configured price with every digit it was configured with, never rounded to cents', async () => {
        const [local, billed] = RECORDED_MODELS.connections;
        const precise: ChatModels = {
            ...RECORDED_MODELS,
            connections: [local, {...billed, models: [{...billed.models[0], price: {input: '2.505', output: '0.00001'}}, billed.models[1]]}],
        };
        await boot(1280, RECORDED_STATUS, 800, precise);
        await openPicker();
        expect(words(row('model-x').querySelector('.lg-chat-model-price'))).toBe('$2.505 in · $0.00001 out');
    });

    it('lets go of its listeners and its placement when it stops showing while open', async () => {
        await boot();
        await openPicker();
        expect(isOpen()).toBe(true);
        const removed = vi.spyOn(document, 'removeEventListener');
        const local = RECORDED_MODELS.connections[0];
        TestBed.inject(Dispatcher).dispatch(chatEvents.modelsLoaded({connections: [{...local, models: [local.models[0]]}], defaultModelName: 'model-a'}));
        await settle();

        expect(trigger(), 'one model left: the picker is gone').toBeNull();
        const capture = (options: unknown) => options === true || (typeof options === 'object' && options !== null && 'capture' in options && options.capture === true);
        const released = removed.mock.calls.filter(([, , options]) => capture(options)).map(([type]) => type);
        removed.mockRestore();
        expect(released, 'the capture-phase Escape and outside handlers, and the tracking').toEqual(
            expect.arrayContaining(['keydown', 'pointerdown', 'animationend', 'transitionend', 'scroll']),
        );
    });

    describe('by keyboard and by closing it (ISC-523)', () => {
        it('disables the spent billed group with its reason and moves over enabled models only, Enter picking', async () => {
            await boot(1280, BILLED_SPENT);
            const [, billed] = groups();
            expect(words(billed.querySelector('.lg-chat-model-reason')), 'the reason on the spent group').toBe('No calls left today');
            expect(rows(billed).every((r) => r.getAttribute('aria-disabled') === 'true'), 'its models disabled').toBe(true);

            // Opened by keyboard: Enter on the focused trigger, focus onto the current pick.
            trigger()!.focus();
            await press('Enter');
            expect(isOpen(), 'Enter on the trigger opens it').toBe(true);
            expect(focused(), 'focus on the current pick').toBe('model-a');

            await press('ArrowDown');
            expect(focused()).toBe('model-b');
            await press('ArrowDown');
            expect(focused(), 'ArrowDown skips the spent models').toBe('model-b');
            await press('Home');
            expect(focused(), 'Home is the first enabled model').toBe('model-a');
            await press('End');
            expect(focused(), 'End is the last enabled model, not a spent one').toBe('model-b');
            await press('ArrowUp');
            expect(focused()).toBe('model-a');
            await press('ArrowDown');

            await press('Enter');
            expect(isOpen(), 'Enter picks and closes').toBe(false);
            expect(trigger()!.getAttribute('aria-label')).toBe('Model: model-b, local');
            expect(document.activeElement, 'focus back on the trigger').toBe(trigger());
        });

        it('closes on Escape and on a click outside without picking, focus back on the trigger, and asks on the pick', async () => {
            await boot(1280, BILLED_SPENT);
            trigger()!.focus();
            await press('Enter');
            await press('ArrowDown');
            await press('Enter');
            expect(trigger()!.getAttribute('aria-label')).toBe('Model: model-b, local');

            // Escape: closed, nothing picked, and the docked drawer stays open.
            trigger()!.focus();
            await press('Enter');
            await press('Home');
            await press('Escape');
            expect(isOpen(), 'Escape closes').toBe(false);
            expect(composer(), 'the docked drawer stays open').not.toBeNull();
            expect(trigger()!.getAttribute('aria-label'), 'Escape picks nothing').toBe('Model: model-b, local');
            expect(document.activeElement, 'focus back on the trigger after Escape').toBe(trigger());

            // A click outside: closed, nothing picked.
            await press('Enter');
            await press('Home');
            expect(isOpen()).toBe(true);
            await userEvent.click(document.querySelector('h1')!);
            await settle();
            expect(isOpen(), 'an outside click closes').toBe(false);
            expect(trigger()!.getAttribute('aria-label'), 'an outside click picks nothing').toBe('Model: model-b, local');
            expect(document.activeElement, 'focus back on the trigger after an outside click').toBe(trigger());

            await askQuestion('What do Kafka offers pay?');
            expect(ask, 'the request names the pick').toHaveBeenLastCalledWith(9, 'What do Kafka offers pay?', {connection: 'PRIMARY', model: 'model-b'});
        });

        it('takes Escape before the modal sheet, which stays open', async () => {
            await boot(375, BILLED_SPENT);
            expect(document.querySelector('dialog[open]'), 'the drawer is the modal sheet').not.toBeNull();
            trigger()!.focus();
            await press('Enter');
            expect(isOpen()).toBe(true);
            await press('Escape');
            expect(isOpen(), 'Escape closes the picker').toBe(false);
            expect(document.querySelector('dialog[open]'), 'and not the sheet').not.toBeNull();
            expect(document.activeElement).toBe(trigger());
        });
    });

    describe('placement and legibility (ISC-524)', () => {
        const CASES = [
            {width: 320, sheet: true},
            {width: 375, sheet: true},
            {width: 820, sheet: false},
            {width: 1440, sheet: false},
        ] as const;

        for (const {width, sheet} of CASES) {
            for (const name of ['lg-light', 'lg-dark'] as const) {
                it(`stays inside the viewport and legible at ${width} px, ${sheet ? 'as the sheet' : 'docked'}, ${name}`, async () => {
                    await boot(width);
                    document.documentElement.setAttribute('data-theme', name);
                    expect(document.querySelector('dialog[open]') !== null, sheet ? 'the modal sheet' : 'docked').toBe(sheet);
                    await openPicker();
                    await frames();

                    const box = panel()!.getBoundingClientRect();
                    const button = trigger()!.getBoundingClientRect();
                    const margin = parseFloat(getComputedStyle(document.documentElement).fontSize);
                    const view = {w: window.visualViewport?.width ?? innerWidth, h: window.visualViewport?.height ?? innerHeight};
                    expect(box.width, 'the panel has a width').toBeGreaterThan(0);
                    expect(box.left, 'inside on the left').toBeGreaterThanOrEqual(margin - 1);
                    expect(box.right, 'inside on the right').toBeLessThanOrEqual(view.w - margin + 1);
                    expect(box.top, 'inside at the top').toBeGreaterThanOrEqual(margin - 1);
                    expect(box.bottom, 'above the trigger').toBeLessThanOrEqual(button.top + 1);
                    if (sheet) expect(Math.abs(box.left - (view.w - box.right)), 'gutter to gutter').toBeLessThanOrEqual(1);
                    else expect(Math.abs(box.right - button.right), 'end-aligned to the trigger').toBeLessThanOrEqual(1);

                    const low = textBoxes(panel()!)
                        .map((e) => {
                            const ink = colour(getComputedStyle(e).color);
                            const under = ground(e);
                            return {text: words(e).slice(0, 24), ratio: contrastRatio(composite(ink, ink.alpha, under), under)};
                        })
                        .filter((t) => t.ratio < 4.5);
                    expect(low, 'every text at least 4.5:1').toEqual([]);
                });
            }
        }

        it('re-places the open panel when the viewport changes, staying end-aligned and inside', async () => {
            await boot(1440);
            await openPicker();
            await frames();
            await page.viewport(1000, 600);
            await frames();

            const box = panel()!.getBoundingClientRect();
            const button = trigger()!.getBoundingClientRect();
            expect(isOpen()).toBe(true);
            expect(Math.abs(box.right - button.right), 'end-aligned after the resize').toBeLessThanOrEqual(1);
            expect(box.bottom).toBeLessThanOrEqual(button.top + 1);
            expect(box.top).toBeGreaterThanOrEqual(0);
            expect(box.right).toBeLessThanOrEqual(innerWidth);
        });
    });
});
