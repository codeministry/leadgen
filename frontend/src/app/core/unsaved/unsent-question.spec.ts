import {forgetUnsent, keepUnsent, keptBeyondThePage, unsentQuestion} from './unsent-question';

afterEach(() => {
    vi.restoreAllMocks();
    forgetUnsent();
    window.sessionStorage.clear();
});

describe('the kept chat question', () => {
    it('lives in the tab\'s storage, so a reload finds it', () => {
        keepUnsent('What came in?');

        expect(window.sessionStorage.getItem('leadgen.chat.unsent')).toBe('What came in?');
        expect(keptBeyondThePage('What came in?')).toBe(true);
    });

    it('a write the storage refused while reads still work falls back to memory, not to an older question (final review, finding 3)', () => {
        keepUnsent('Older?');
        vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
            throw new DOMException('quota', 'QuotaExceededError');
        });

        keepUnsent('Newer?');

        expect(unsentQuestion()).toBe('Newer?');
        expect(keptBeyondThePage('Newer?')).toBe(false);
    });

    it('forgets only the question named, when one is', () => {
        keepUnsent('Mine?');

        forgetUnsent('Somebody else\'s?');
        expect(unsentQuestion()).toBe('Mine?');
        forgetUnsent('Mine?');
        expect(unsentQuestion()).toBeNull();
    });
});
