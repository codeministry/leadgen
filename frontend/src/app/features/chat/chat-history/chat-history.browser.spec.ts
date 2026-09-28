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

const FIVE: readonly ConversationSummary[] = [
    {id: 9, title: 'Kafka rates', updatedAt: today, lastActivityAt: today},
    {id: 8, title: 'Spring remote', updatedAt: today, lastActivityAt: today},
    {id: 7, title: 'Kafka in Cologne', updatedAt: today, lastActivityAt: today},
    {id: 6, title: 'Angular leads', updatedAt: today, lastActivityAt: today},
    {id: 5, title: 'Rates in general', updatedAt: today, lastActivityAt: today},
];

/**
 * Deleting several conversations at once (ISC-478): a select mode in the list's head, a checkbox per
 * row, select all over the rows shown, one confirmation naming the count, one request.
 */
describe('deleting several conversations (ISC-478)', () => {
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

    afterEach(() => (fixture.nativeElement as HTMLElement).remove());

    const q = <T extends Element = HTMLElement>(selector: string) => document.querySelector<T>(`.lg-chat-history ${selector}`);
    const rows = () => [...document.querySelectorAll('.lg-chat-history .lg-chat-row-title')].map((t) => t.textContent?.trim());
    const checks = () => [...document.querySelectorAll<HTMLInputElement>('.lg-chat-history .lg-chat-row-check')];
    const dialog = () => document.querySelector<HTMLDialogElement>('dialog.lg-chat-delete')!;
    const bulk = () => http.match((r) => r.url === `${BASE}/bulk-delete`);

    async function click(element: HTMLElement | null): Promise<void> {
        expect(element).not.toBeNull();
        element!.click();
        await settle();
    }

    it('deletes the three picked in one request, takes their rows away and leaves the mode', async () => {
        await start('/dashboard?chat=list', 1024);
        http.expectOne(BASE).flush(FIVE);
        await settle();
        expect(checks(), 'no checkbox before the mode').toHaveLength(0);

        await click(q('.lg-chat-select-toggle'));
        expect(checks()).toHaveLength(5);
        expect(checks().every((c) => c.closest('.lg-chat-row') !== null), 'each box inside its row').toBe(true);
        for (const i of [0, 2]) await click(checks()[i]);
        // The row is the label: a click on the title ticks the box as well.
        await click([...document.querySelectorAll<HTMLElement>('.lg-chat-history .lg-chat-row-title')][4]);
        expect(checks()[4].checked).toBe(true);
        expect(q('.lg-chat-select-n')!.textContent!.trim(), 'the number alone on screen').toBe('3');
        expect(q('.lg-chat-select-count .sr-only')!.textContent!.trim()).toBe('3 selected');

        await click(q<HTMLButtonElement>('.lg-chat-bulk-delete'));
        expect(dialog().open).toBe(true);
        expect(dialog().textContent).toContain('Delete 3 conversations?');
        expect(document.activeElement?.classList.contains('lg-chat-delete-cancel')).toBe(true);

        await click(dialog().querySelector<HTMLButtonElement>('.lg-chat-delete-confirm'));
        const requests = bulk();
        expect(requests, 'one request').toHaveLength(1);
        expect(requests[0].request.method).toBe('POST');
        expect(requests[0].request.body).toEqual({ids: [9, 7, 5]});
        requests[0].flush({deleted: [5, 7, 9]});
        await settle();

        expect(rows()).toEqual(['Spring remote', 'Angular leads']);
        expect(checks(), 'the mode ended').toHaveLength(0);
        http.expectNone((r) => r.method === 'DELETE');
    });

    it('selects exactly the hits of a search, and the dialog names their count with Cancel first', async () => {
        await start('/dashboard?chat=list', 1024);
        http.expectOne(BASE).flush(FIVE);
        await settle();

        const search = q<HTMLInputElement>('.lg-chat-search-input')!;
        search.value = 'kafka';
        search.dispatchEvent(new Event('input'));
        await new Promise((resolve) => setTimeout(resolve, 300));
        await settle();
        http.expectOne((r) => r.url === BASE && r.params.get('q') === 'kafka').flush([FIVE[0], FIVE[2]]);
        await settle();

        await click(q('.lg-chat-select-toggle'));
        await click(q<HTMLInputElement>('.lg-chat-select-all'));
        expect(checks().every((c) => c.checked)).toBe(true);
        expect(q('.lg-chat-select-n')!.textContent!.trim()).toBe('2');

        await click(q<HTMLButtonElement>('.lg-chat-bulk-delete'));
        expect(dialog().textContent).toContain('Delete 2 conversations?');
        expect(document.activeElement?.classList.contains('lg-chat-delete-cancel')).toBe(true);
        await click(dialog().querySelector<HTMLButtonElement>('.lg-chat-delete-cancel'));
        expect(dialog().open).toBe(false);
        expect(bulk(), 'nothing sent on cancel').toHaveLength(0);

        await click(q<HTMLButtonElement>('.lg-chat-bulk-delete'));
        await click(dialog().querySelector<HTMLButtonElement>('.lg-chat-delete-confirm'));
        const [request] = bulk();
        expect(request.request.body).toEqual({ids: [9, 7]});
        request.flush({deleted: [7, 9]});
        await settle();
    });

    it('leaves the thread for a new conversation when the open one is among them', async () => {
        await start('/dashboard?chat=9', 1440);
        http.expectOne(`${BASE}/9`).flush({id: 9, title: 'Kafka rates', pinnedOfferId: null, turns: [], updatedAt: today});
        http.match(BASE).forEach((r) => r.flush(FIVE));
        await settle();

        await click(q('.lg-chat-select-toggle'));
        await click(checks()[0]);
        await click(checks()[1]);
        await click(q<HTMLButtonElement>('.lg-chat-bulk-delete'));
        await click(dialog().querySelector<HTMLButtonElement>('.lg-chat-delete-confirm'));
        bulk()[0].flush({deleted: [8, 9]});
        await settle();

        expect(router.url).toContain('chat=new');
    });
});
