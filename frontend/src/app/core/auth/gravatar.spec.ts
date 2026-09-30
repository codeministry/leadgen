import {gravatarUrl, initials} from './gravatar';

describe('gravatarUrl', () => {
    it('hashes the trimmed, lower-cased address with SHA-256 and asks for no placeholder', async () => {
        // The documented example: "MyEmailAddress@example.com " and its SHA-256.
        const url = await gravatarUrl(' MyEmailAddress@example.com ');

        expect(url).toBe(
            'https://gravatar.com/avatar/84059b07d4be67b806386c0aad8070a23f18836bbaae342275dc0a83414c32ee?s=80&d=404',
        );
    });

    it('asks for nothing without an address', async () => {
        expect(await gravatarUrl(null)).toBeNull();
        expect(await gravatarUrl('  ')).toBeNull();
    });
});

describe('initials', () => {
    it('takes the first and last word, and falls back to one letter or a question mark', () => {
        expect(initials('Ada Byron Lovelace')).toBe('AL');
        expect(initials('ada')).toBe('A');
        expect(initials('ada.lovelace@example.com')).toBe('AL');
        expect(initials('')).toBe('?');
        expect(initials(null)).toBe('?');
    });
});
