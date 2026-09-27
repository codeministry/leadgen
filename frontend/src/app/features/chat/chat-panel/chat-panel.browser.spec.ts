import {Component} from '@angular/core';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {page} from 'vitest/browser';
import {Subject} from 'rxjs';
import {ChatApi} from '@core/api/chat.api';
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
