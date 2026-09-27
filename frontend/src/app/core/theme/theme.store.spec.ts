import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {TestBed} from '@angular/core/testing';
import {Dispatcher} from '@ngrx/signals/events';
import {themeEvents} from './theme.events';
import {ThemeStore} from './theme.store';
import {DATA_THEME_ATTR, THEME_STORAGE_KEY, THEME_SURFACE_HEX} from './theme.model';

const THEME_COLOR_META = 'meta[name="theme-color"]';

function themeColor(): string | null {
    return document.querySelector<HTMLMetaElement>(THEME_COLOR_META)?.content ?? null;
}

describe('ThemeStore', () => {
    beforeEach(() => {
        localStorage.removeItem(THEME_STORAGE_KEY);
        document.documentElement.removeAttribute(DATA_THEME_ATTR);
        document.querySelector(THEME_COLOR_META)?.remove();
    });

    it('starts on dark with nothing stored, rather than following the OS', () => {
        const store = TestBed.inject(ThemeStore);
        TestBed.tick();

        expect(store.preference()).toBe('dark');
        expect(document.documentElement.getAttribute(DATA_THEME_ATTR)).toBe('lg-dark');
    });

    // The default has to survive a light-set machine, which is the whole reason it stopped
    // being `system`: the attribute is what wins over the prefers-color-scheme query.
    it('stays dark with nothing stored even when the OS prefers light', () => {
        const store = TestBed.inject(ThemeStore);
        const dispatcher = TestBed.inject(Dispatcher);

        dispatcher.dispatch(themeEvents.systemChanged(false));
        TestBed.tick();

        expect(store.theme()).toBe('lg-dark');
        expect(document.documentElement.getAttribute(DATA_THEME_ATTR)).toBe('lg-dark');
    });

    it('writes the attribute for an explicit choice and takes it back off for system', () => {
        const store = TestBed.inject(ThemeStore);
        const dispatcher = TestBed.inject(Dispatcher);

        dispatcher.dispatch(themeEvents.chosen('dark'));
        TestBed.tick();
        expect(store.theme()).toBe('lg-dark');
        expect(document.documentElement.getAttribute(DATA_THEME_ATTR)).toBe('lg-dark');

        dispatcher.dispatch(themeEvents.chosen('system'));
        TestBed.tick();
        expect(document.documentElement.hasAttribute(DATA_THEME_ATTR)).toBe(false);
    });

    it('resolves an explicitly chosen system against the OS', () => {
        const store = TestBed.inject(ThemeStore);
        const dispatcher = TestBed.inject(Dispatcher);

        dispatcher.dispatch(themeEvents.chosen('system'));
        dispatcher.dispatch(themeEvents.systemChanged(true));
        TestBed.tick();

        expect(store.preference()).toBe('system');
        expect(store.theme()).toBe('lg-dark');
    });

    it('persists the preference, not the resolved theme', () => {
        const dispatcher = TestBed.inject(Dispatcher);
        TestBed.inject(ThemeStore);

        dispatcher.dispatch(themeEvents.chosen('light'));
        TestBed.tick();

        expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light');
    });
});

/**
 * ISC-333: an installed window paints its title bar from `meta[name=theme-color]`, and the
 * browser reads that tag again on every change. The store keeps it on the resolved theme's
 * surface, so the bar matches the page in both themes and on the OS switch under `system`.
 */
describe('ThemeStore and the theme-color meta (ISC-333)', () => {
    beforeEach(() => {
        localStorage.removeItem(THEME_STORAGE_KEY);
        document.documentElement.removeAttribute(DATA_THEME_ATTR);
        document.querySelector(THEME_COLOR_META)?.remove();
    });

    it('writes the dark surface on boot and follows a switch to light', () => {
        TestBed.inject(ThemeStore);
        const dispatcher = TestBed.inject(Dispatcher);
        TestBed.tick();
        expect(themeColor()).toBe(THEME_SURFACE_HEX['lg-dark']);

        dispatcher.dispatch(themeEvents.chosen('light'));
        TestBed.tick();
        expect(themeColor()).toBe(THEME_SURFACE_HEX['lg-light']);
    });

    it('creates the meta when the document has none, and reuses it afterwards', () => {
        TestBed.inject(ThemeStore);
        const dispatcher = TestBed.inject(Dispatcher);
        TestBed.tick();
        expect(document.querySelectorAll(THEME_COLOR_META)).toHaveLength(1);

        dispatcher.dispatch(themeEvents.chosen('light'));
        TestBed.tick();
        expect(document.querySelectorAll(THEME_COLOR_META)).toHaveLength(1);
    });

    it('under system, follows the OS rather than the stored preference', () => {
        TestBed.inject(ThemeStore);
        const dispatcher = TestBed.inject(Dispatcher);

        dispatcher.dispatch(themeEvents.chosen('system'));
        dispatcher.dispatch(themeEvents.systemChanged(false));
        TestBed.tick();
        expect(themeColor()).toBe(THEME_SURFACE_HEX['lg-light']);

        dispatcher.dispatch(themeEvents.systemChanged(true));
        TestBed.tick();
        expect(themeColor()).toBe(THEME_SURFACE_HEX['lg-dark']);
    });
});

/**
 * The inline script in `src/index.html` writes the same meta before Angular boots, and it
 * cannot import the constant, so it carries the two hex literals by hand. This holds them to
 * `THEME_SURFACE_HEX`: a stylesheet change that moves a surface fails here rather than as a
 * title bar that flashes the old colour and then corrects itself.
 */
describe('the inline theme script (ISC-333)', () => {
    const html = readFileSync(resolve(process.cwd(), 'src/index.html'), 'utf8');
    const parsed = new DOMParser().parseFromString(html, 'text/html');
    const script = parsed.querySelector('script')?.textContent ?? '';

    it('carries both surfaces, each equal to THEME_SURFACE_HEX', () => {
        expect(script).toContain(`'lg-light': '${THEME_SURFACE_HEX['lg-light']}'`);
        expect(script).toContain(`'lg-dark': '${THEME_SURFACE_HEX['lg-dark']}'`);
    });

    /**
     * Runs the script itself, against a fresh document and a stubbed storage and media
     * query, because a text match is satisfied by the comments alone. The four free names
     * the script reaches for are bound as parameters, so nothing leaks into the test's own
     * document. `withStaticMeta` is the shipped head, which carries the dark tag already;
     * without it the script has to create the tag rather than skip the write.
     */
    function runInlineScript(stored: string | null, osDark: boolean, withStaticMeta = true): Document {
        const doc = document.implementation.createHTMLDocument('Lead Generation');
        if (withStaticMeta) {
            const meta = doc.createElement('meta');
            meta.name = 'theme-color';
            meta.content = THEME_SURFACE_HEX['lg-dark'];
            doc.head.appendChild(meta);
        }
        const localStorage = {getItem: (key: string) => (key === 'lg-theme' ? stored : null)};
        const window = {matchMedia: () => ({matches: osDark})};
        const navigator = {language: 'en'};
        new Function('document', 'window', 'localStorage', 'navigator', script)(doc, window, localStorage, navigator);
        return doc;
    }

    function outcome(doc: Document): {theme: string | null; color: string | null; metas: number} {
        return {
            theme: doc.documentElement.getAttribute(DATA_THEME_ATTR),
            color: doc.querySelector<HTMLMetaElement>(THEME_COLOR_META)?.content ?? null,
            metas: doc.querySelectorAll(THEME_COLOR_META).length,
        };
    }

    const dark = THEME_SURFACE_HEX['lg-dark'];
    const light = THEME_SURFACE_HEX['lg-light'];

    it.each([
        ['dark', false, 'lg-dark', dark],
        ['dark', true, 'lg-dark', dark],
        ['light', false, 'lg-light', light],
        ['light', true, 'lg-light', light],
        ['system', false, null, light],
        ['system', true, null, dark],
        [null, false, 'lg-dark', dark],
        [null, true, 'lg-dark', dark],
        ['garbage', false, 'lg-dark', dark],
        ['garbage', true, 'lg-dark', dark],
    ])('stored %s, OS dark %s: data-theme %s, theme-color %s, before the first paint', (stored, osDark, theme, color) => {
        expect(outcome(runInlineScript(stored, osDark))).toEqual({theme, color, metas: 1});
    });

    it('creates the meta when the head carries none, and never a second one when it does', () => {
        expect(outcome(runInlineScript('light', false, false))).toEqual({theme: 'lg-light', color: light, metas: 1});
        expect(outcome(runInlineScript('light', false, true))).toEqual({theme: 'lg-light', color: light, metas: 1});
    });
});
