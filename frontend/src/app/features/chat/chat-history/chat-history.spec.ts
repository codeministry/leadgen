import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {injectDispatch} from '@ngrx/signals/events';
import {ConversationSummary} from '@core/model/chat';
import {chatEvents} from '@core/store/chat.events';
import {ChatHistory} from './chat-history';
import {groupByActivity} from './time-groups';

const BASE = '/api/v1/chat/conversations';
/** Sunday 27 September 2026, 12:00 in Berlin: the week began on Monday the 21st. */
const NOW = new Date('2026-09-27T10:00:00Z');

function summary(id: number, lastActivityAt: string, title = `Conversation ${id}`): ConversationSummary {
    return {id, title, updatedAt: lastActivityAt, lastActivityAt};
}

/** Seven conversations: two today, two yesterday, three days, twenty days and ninety days back. */
const SEVEN: readonly ConversationSummary[] = [
    summary(1, '2026-09-27T06:00:00Z'),
    summary(2, '2026-09-27T09:30:00Z'),
    summary(3, '2026-09-26T08:00:00Z'),
    summary(4, '2026-09-26T15:00:00Z'),
    summary(5, '2026-09-24T10:00:00Z'),
    summary(6, '2026-09-07T10:00:00Z'),
    summary(7, '2026-06-29T10:00:00Z'),
];

/** The time groups of the conversation list (ISC-447), as a pure rule over a fixed clock and zone. */
describe('groupByActivity (ISC-447)', () => {
    it('puts seven conversations into five groups in order, newest first within each', () => {
        const groups = groupByActivity(SEVEN, NOW, 'Europe/Berlin');

        expect(groups.map((g) => g.key)).toEqual(['today', 'yesterday', 'week', 'month', 'older']);
        expect(groups.map((g) => g.rows.map((r) => r.id))).toEqual([[2, 1], [4, 3], [5], [6], [7]]);
    });

    it('draws no group for a day nobody talked on', () => {
        const groups = groupByActivity(
            SEVEN.filter((c) => c.id !== 3 && c.id !== 4),
            NOW,
            'Europe/Berlin',
        );

        expect(groups.map((g) => g.key)).toEqual(['today', 'week', 'month', 'older']);
    });

    it('reads the day in the given zone, not in UTC', () => {
        // 23:30 UTC on the 26th is already the 27th in Berlin, and still the 26th in New York.
        const late = [summary(1, '2026-09-26T23:30:00Z')];

        expect(groupByActivity(late, NOW, 'Europe/Berlin')[0].key).toBe('today');
        expect(groupByActivity(late, NOW, 'America/New_York')[0].key).toBe('yesterday');
    });
});

/** The list as drawn: headings in order, none for an empty group, the search, the rename. */
describe('ChatHistory (ISC-447, ISC-449, ISC-450)', () => {
    let fixture: ComponentFixture<ChatHistory>;
    let http: HttpTestingController;
    let dispatch: ReturnType<typeof injectDispatch<typeof chatEvents>>;

    beforeEach(() => {
        // Only the clock, and it keeps running: `debounceTime` re-reads `Date.now()` and waits forever on a frozen one.
        vi.useFakeTimers({toFake: ['Date'], shouldAdvanceTime: true});
        vi.setSystemTime(NOW);
        TestBed.configureTestingModule({providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]});
        http = TestBed.inject(HttpTestingController);
        dispatch = TestBed.runInInjectionContext(() => injectDispatch(chatEvents));
        fixture = TestBed.createComponent(ChatHistory);
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    async function settle(): Promise<void> {
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
    }

    const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
    const root = () => fixture.nativeElement as HTMLElement;
    const headings = () => [...root().querySelectorAll('.lg-chat-group-heading')].map((h) => h.textContent?.trim());
    const titles = () => [...root().querySelectorAll('.lg-chat-row-title')].map((t) => t.textContent?.trim());

    it('draws the five headings in order and no heading for an empty group', async () => {
        dispatch.listLoaded(SEVEN);
        await settle();
        expect(headings()).toEqual(['Today', 'Yesterday', 'This week', 'This month', 'Older']);
        expect(titles()).toEqual([
            'Conversation 2',
            'Conversation 1',
            'Conversation 4',
            'Conversation 3',
            'Conversation 5',
            'Conversation 6',
            'Conversation 7',
        ]);

        dispatch.listLoaded(SEVEN.filter((c) => c.id !== 3 && c.id !== 4));
        await settle();
        expect(headings()).toEqual(['Today', 'This week', 'This month', 'Older']);
    });

    it('searches on the server, keeps the result while it loads, and says so when nothing matches', async () => {
        dispatch.listLoaded(SEVEN);
        await settle();
        const field = root().querySelector<HTMLInputElement>('input[type="search"]')!;
        field.value = '  kotlin remote ';
        field.dispatchEvent(new Event('input'));
        await settle();
        // Debounced: nothing is asked yet, and the previous result stays.
        http.expectNone((r) => r.url === BASE);
        expect(root().querySelectorAll('.lg-chat-row')).toHaveLength(7);

        await wait(400);
        await settle();
        const request = http.expectOne((r) => r.url === BASE);
        expect(request.request.params.get('q')).toBe('kotlin remote');
        // While it runs, the previous result is still drawn and marked busy.
        expect(root().querySelector('.lg-chat-groups')?.getAttribute('aria-busy')).toBe('true');
        expect(root().querySelectorAll('.lg-chat-row')).toHaveLength(7);
        request.flush([]);
        await settle();

        expect(headings()).toEqual([]);
        expect(root().querySelector('.lg-chat-no-match')?.textContent).toContain('kotlin remote');
        root().querySelector<HTMLButtonElement>('.lg-chat-clear-search')!.click();
        await wait(400);
        await settle();
        const again = http.expectOne((r) => r.url === BASE);
        expect(again.request.params.has('q')).toBe(false);
    });

    it('renames inline: Escape restores the old title, Enter saves the trimmed one', async () => {
        dispatch.listLoaded(SEVEN);
        await settle();
        root().querySelector<HTMLButtonElement>('.lg-chat-rename-action')!.click();
        await settle();
        let input = root().querySelector<HTMLInputElement>('.lg-chat-rename')!;
        expect(input.value).toBe('Conversation 2');
        expect(input.maxLength).toBe(120);
        input.value = 'Other';
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}));
        await settle();
        http.expectNone((r) => r.method === 'PATCH');
        expect(titles()[0]).toBe('Conversation 2');

        root().querySelector<HTMLButtonElement>('.lg-chat-rename-action')!.click();
        await settle();
        input = root().querySelector<HTMLInputElement>('.lg-chat-rename')!;
        input.value = '  Kafka rates  ';
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true}));
        await settle();
        const patch = http.expectOne(`${BASE}/2`);
        expect(patch.request.method).toBe('PATCH');
        expect(patch.request.body).toEqual({title: 'Kafka rates'});
        patch.flush({id: 2, title: 'Kafka rates', pinnedOfferId: null, turns: [], updatedAt: '2026-09-27T09:30:00Z'});
        await settle();
        expect(titles()[0]).toBe('Kafka rates');
    });

    it('an emptied title is sent empty, and the derived title the server answers is shown', async () => {
        dispatch.listLoaded(SEVEN);
        await settle();
        root().querySelector<HTMLButtonElement>('.lg-chat-rename-action')!.click();
        await settle();
        const input = root().querySelector<HTMLInputElement>('.lg-chat-rename')!;
        input.value = '   ';
        input.dispatchEvent(new Event('blur'));
        await settle();
        const patch = http.expectOne(`${BASE}/2`);
        expect(patch.request.body).toEqual({title: ''});
        patch.flush({id: 2, title: 'Which remote Spring projects…', pinnedOfferId: null, turns: [], updatedAt: '2026-09-27T09:30:00Z'});
        await settle();
        expect(titles()[0]).toBe('Which remote Spring projects…');
    });
});
