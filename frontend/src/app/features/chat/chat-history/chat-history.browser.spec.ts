import {Component} from '@angular/core';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {page} from 'vitest/browser';
import {ConversationSummary} from '@core/model/chat';
import {App} from '../../../app';

@Component({template: '<h1>screen</h1>'})
class Screen {}

const BASE = '/api/v1/chat/conversations';
const today = new Date().toISOString();
const LIST: readonly ConversationSummary[] = [
    {id: 9, title: 'Kafka rates', updatedAt: today, lastActivityAt: today},
    {id: 8, title: 'Spring remote', updatedAt: today, lastActivityAt: today},
];

/**
 * Deleting a conversation (ISC-448, the web half): behind the row's "⋯", confirmed in a native modal
 * dialog that focuses Cancel. In a real browser, because `showModal`, the top layer and a popover are
 * what the claim is about, and jsdom implements none of them.
 */
describe('deleting a conversation (ISC-448)', () => {
    let fixture: ComponentFixture<App>;
    let http: HttpTestingController;
    let router: Router;

    async function settle(): Promise<void> {
        for (let i = 0; i < 4; i++) {
            await new Promise((resolve) => setTimeout(resolve, 0));
            fixture.detectChanges();
        }
        await new Promise((resolve) => requestAnimationFrame(resolve));
    }

    async function start(url: string, width: number): Promise<void> {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{path: 'dashboard', component: Screen}])],
        });
        http = TestBed.inject(HttpTestingController);
        router = TestBed.inject(Router);
        await page.viewport(width, 800);
        fixture = TestBed.createComponent(App);
        document.body.appendChild(fixture.nativeElement);
        await router.navigateByUrl(url);
        await settle();
        http.match((r) => r.url === '/api/v1/chat/capability').forEach((r) => r.flush({present: true}));
        await settle();
    }

    afterEach(() => {
        (fixture.nativeElement as HTMLElement).remove();
    });

    const rows = () => [...document.querySelectorAll('.lg-chat-history .lg-chat-row-title')].map((t) => t.textContent?.trim());
    const dialog = () => document.querySelector<HTMLDialogElement>('dialog.lg-chat-delete')!;

    async function askToDelete(index: number): Promise<void> {
        document.querySelectorAll<HTMLButtonElement>('.lg-chat-history .lg-chat-more')[index].click();
        await settle();
        const menu = document.querySelectorAll<HTMLElement>('.lg-chat-history .lg-chat-menu')[index];
        expect(menu.matches(':popover-open')).toBe(true);
        menu.querySelector<HTMLButtonElement>('.lg-chat-delete-action')!.click();
        await settle();
    }

    it('cancelling changes nothing, and Cancel has the focus first', async () => {
        await start('/dashboard?chat=list', 1024);
        http.expectOne(BASE).flush(LIST);
        await settle();

        await askToDelete(1);
        expect(dialog().open).toBe(true);
        expect(dialog().textContent).toContain('Spring remote');
        expect(document.activeElement?.classList.contains('lg-chat-delete-cancel')).toBe(true);

        dialog().querySelector<HTMLButtonElement>('.lg-chat-delete-cancel')!.click();
        await settle();
        expect(dialog().open).toBe(false);
        http.expectNone((r) => r.method === 'DELETE');
        expect(rows()).toEqual(['Kafka rates', 'Spring remote']);
    });

    it('confirming deletes on the server and takes the row away', async () => {
        await start('/dashboard?chat=list', 1024);
        http.expectOne(BASE).flush(LIST);
        await settle();

        await askToDelete(1);
        dialog().querySelector<HTMLButtonElement>('.lg-chat-delete-confirm')!.click();
        await settle();
        const request = http.expectOne(`${BASE}/8`);
        expect(request.request.method).toBe('DELETE');
        request.flush(null);
        await settle();

        expect(dialog().open).toBe(false);
        expect(rows()).toEqual(['Kafka rates']);
        // Gone on the server too: reading it again is the 404 the drawer shows as missing.
        await router.navigateByUrl('/dashboard?chat=8');
        await settle();
        http.expectOne(`${BASE}/8`).flush(null, {status: 404, statusText: 'Not Found'});
        await settle();
        expect(document.querySelector('.lg-chat-restart')).not.toBeNull();
    });

    it('deleting the open conversation from the rail lands on ?chat=new', async () => {
        await start('/dashboard?chat=9', 1440);
        http.expectOne(`${BASE}/9`).flush({id: 9, title: 'Kafka rates', pinnedOfferId: null, turns: [], updatedAt: today});
        http.match(BASE).forEach((r) => r.flush(LIST));
        await settle();

        await askToDelete(0);
        dialog().querySelector<HTMLButtonElement>('.lg-chat-delete-confirm')!.click();
        await settle();
        http.expectOne(`${BASE}/9`).flush(null);
        await settle();

        expect(router.url).toContain('chat=new');
    });
});
