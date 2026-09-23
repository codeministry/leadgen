import de from '../../../../public/i18n/de.json';
import en from '../../../../public/i18n/en.json';

/** Every leaf key with its dotted path, so a group added to one catalog only shows up by name. */
function keys(catalog: unknown, prefix = ''): string[] {
    if (typeof catalog !== 'object' || catalog === null) {
        return [prefix.slice(0, -1)];
    }
    return Object.entries(catalog).flatMap(([key, value]) => keys(value, `${prefix}${key}.`));
}

/**
 * The two catalogs carry the same keys. Transloco falls back to English for a key German
 * lacks, so the defect this catches is invisible on screen: a sentence in the wrong
 * language, on the one screen nobody opened in German. Measured on 2026-09-23 the catalogs
 * were already equal at 464 keys, which is why this covers the whole catalog rather than
 * the group that was added that day.
 */
describe('i18n catalogs', () => {
    it('hold the same keys in English and German', () => {
        const english = keys(en).sort();
        const german = keys(de).sort();

        expect(english.filter((key) => !german.includes(key))).toEqual([]);
        expect(german.filter((key) => !english.includes(key))).toEqual([]);
    });

    it('name a count as an ICU plural wherever a toast carries one', () => {
        // A `count` key without a plural is "1 offers" on the one day a run finds exactly one.
        for (const catalog of [en.toast, de.toast]) {
            for (const [key, text] of Object.entries(catalog)) {
                if (key.startsWith('count')) {
                    expect(text, key).toContain('plural');
                }
            }
        }
    });
});
