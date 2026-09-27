import {Component} from '@angular/core';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router, withComponentInputBinding} from '@angular/router';
import {Dispatcher} from '@ngrx/signals/events';
import {page, userEvent} from 'vitest/browser';
import {Subject} from 'rxjs';
import {ChatApi} from '@core/api/chat.api';
import {ChatEvent} from '@core/model/chat';
import {chatEvents} from '@core/store/chat.events';
import {ChatStore} from '@core/store/chat.store';
import {App} from '../../app';

@Component({template: '<h1>screen</h1><p>A page the chat docks beside.</p>'})
class Screen {}

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
                        {path: 'shortlist/:id', component: Screen},
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

        // The streaming mark is the busy dot's colour: a model is writing, nothing else.
        const mark = q('.lg-chat-minibar-busy');
        expect(mark).not.toBeNull();
        const ai = document.createElement('span');
        ai.style.backgroundColor = 'var(--lg-ai)';
        document.body.appendChild(ai);
        expect(getComputedStyle(mark!).backgroundColor).toBe(getComputedStyle(ai).backgroundColor);
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
        expect(q('.lg-chat-pin')?.textContent).toContain('2291');

        const input = q<HTMLTextAreaElement>('.lg-chat-input')!;
        input.value = 'Does it fit?';
        input.dispatchEvent(new Event('input'));
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true}));
        await settle();
        const create = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/chat/conversations');
        expect(create.request.body).toEqual({pinnedOfferId: 2291});
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
});
