import {InjectionToken} from '@angular/core';
import DOMPurify, {Config} from 'dompurify';
import {Marked, Tokens} from 'marked';
import {ChatSource, ChatSourceKind} from '@core/model/chat';
import {LG_ICONS, LgIconName} from '@shared/icon/lucide-icons';

/** One row an answer names, as the server resolved it: `cite:offer/12`, `cite:application/3`. */
export interface Cite {
    readonly kind: ChatSourceKind;
    readonly id: number;
}

const CITE = /^cite:(offer|application)\/([1-9]\d*)$/;
const UNVERIFIED = /^⟨unverified:([^⟩\s]{1,64})⟩/;
/** A scheme that is not http(s): such a link is rendered as its text alone. A relative href has no scheme. */
const FOREIGN_SCHEME = /^\s*(?!https?:)[a-z][a-z0-9+.-]*:/i;
/** How much of a model-written address is shown: enough to recognise the host, never a whole query. */
const SHOWN_URL = 40;

/**
 * The one attribute the renderer's own placeholders carry: this render's mint and the slot's index.
 * It says nothing by itself — what a slot becomes is looked up in a map that never leaves the render.
 */
const SLOT = 'data-lg-slot';

/**
 * How DOMPurify runs: its HTML-only profile (no svg, no MathML), minus every element and attribute
 * that would make the browser fetch something on render, submit something, navigate or restyle the
 * page. `href` is among them: no element that leaves the sanitizer has one, and the pills get theirs
 * afterwards from the slot map. `data-*` is off except the slot.
 */
const SANITIZE: Config = {
    USE_PROFILES: {html: true},
    ALLOW_DATA_ATTR: false,
    ADD_ATTR: [SLOT],
    FORBID_TAGS: [
        'img', 'picture', 'source', 'track', 'video', 'audio', 'image', 'svg', 'math',
        'form', 'input', 'button', 'select', 'textarea', 'style', 'iframe', 'frame',
        'object', 'embed', 'link', 'meta', 'base', 'map', 'area',
    ],
    FORBID_ATTR: ['style', 'srcset', 'background', 'poster', 'ping', 'action', 'formaction', 'href', 'xlink:href', 'target', 'rel'],
    // Any other URI attribute: http(s), or no scheme at all; mailto, data and javascript stay dead.
    ALLOWED_URI_REGEXP: /^(?:https?:|[^a-z]|[a-z+.-]+(?:[^a-z+.:-]|$))/i,
};

/** What a slot becomes, decided by the renderer from the server's citation and nothing the answer wrote. */
type Slot =
    | {readonly kind: 'cite'; readonly href: string; readonly cite: string; readonly title: string | null}
    | {readonly kind: 'unverified'; readonly id: string};

export function parseCite(href: string): Cite | null {
    const match = CITE.exec(href);
    return match === null ? null : {kind: match[1] === 'offer' ? 'OFFER' : 'APPLICATION', id: Number(match[2])};
}

/** Where a cited row opens, as router commands: an offer in the shortlist, an application on the board. */
export function citeCommands(cite: Cite): unknown[] {
    // The wire's ChatSource carries no offer id for an application, so an application opens the
    // board, where it lives, rather than its offer's detail.
    return cite.kind === 'OFFER' ? ['/shortlist', cite.id] : ['/pipeline'];
}

/** What the renderer needs from the component: the URL a cite opens, the row behind it, the words. */
export interface AnswerContext {
    href(cite: Cite): string;
    source(cite: Cite): ChatSource | undefined;
    unverifiedWord(): string;
    citeLabel(n: string, title: string | null): string;
}

function escapeHtml(value: string): string {
    return value.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

/** A model-written address as plain text: no scheme, cut short, so a query full of notes is never repeated whole. */
function shownUrl(href: string): string {
    const bare = href.trim().replace(/^https?:\/\//i, '');
    return bare.length > SHOWN_URL ? `${bare.slice(0, SHOWN_URL - 1)}…` : bare;
}

/** Unguessable, and drawn with `getRandomValues` because `randomUUID` needs a secure context a LAN deploy lacks. */
function freshMint(): string {
    const bytes = new Uint32Array(4);
    crypto.getRandomValues(bytes);
    return [...bytes].map((b) => b.toString(36)).join('');
}

/**
 * Nothing that leaves the sanitizer acts like one of ours: every `lg-*` class the component
 * matches on and styles goes, and an anchor or an image-map area loses whatever would navigate.
 * `SANITIZE` already forbids those attributes; this is the second line, per element.
 */
function disarm(node: Element): void {
    const kept = [...node.classList].filter((c) => !c.startsWith('lg-'));
    if (kept.length !== node.classList.length) {
        if (kept.length === 0) node.removeAttribute('class');
        else node.setAttribute('class', kept.join(' '));
    }
    if (node.tagName === 'A' || node.tagName === 'AREA') {
        for (const name of ['href', 'xlink:href', 'target', 'rel', 'ping']) node.removeAttribute(name);
    }
}

/**
 * A lucide icon as an element, for the one that has to live inside rendered Markdown. It is built
 * from the icon table — never from the answer — and cloned into each mark after DOMPurify ran,
 * because the sanitizer forbids every svg the model could write.
 */
function iconSvg(name: LgIconName, size: number): Element {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    const attrs: Record<string, string> = {
        width: String(size), height: String(size), viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor',
        'stroke-width': '2', 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true',
        class: 'lg-chat-unverified-icon',
    };
    for (const [k, v] of Object.entries(attrs)) svg.setAttribute(k, v);
    for (const [tag, shape] of LG_ICONS[name]) {
        const part = document.createElementNS('http://www.w3.org/2000/svg', tag);
        for (const [k, v] of Object.entries(shape)) if (v !== undefined) part.setAttribute(k, String(v));
        svg.append(part);
    }
    return svg;
}

/**
 * Replaces each slot placeholder with the element its map entry describes. The placeholder's own
 * attributes are never read beyond the slot key, and are dropped with it: a placeholder that is not
 * the tag its entry expects, names another render's mint, or names a slot already used stays inert.
 */
function fill(fragment: DocumentFragment, mint: string, slots: readonly Slot[], context: AnswerContext, glyph: Element): void {
    const used = new Set<number>();
    for (const placeholder of [...fragment.querySelectorAll(`[${SLOT}]`)]) {
        const key = placeholder.getAttribute(SLOT) ?? '';
        placeholder.removeAttribute(SLOT);
        const dot = key.lastIndexOf('.');
        const index = key.slice(0, dot) === mint ? Number(key.slice(dot + 1)) : Number.NaN;
        const slot = Number.isInteger(index) && !used.has(index) ? slots[index] : undefined;
        if (slot === undefined || placeholder.tagName !== (slot.kind === 'cite' ? 'A' : 'SPAN')) continue;
        used.add(index);
        const doc = placeholder.ownerDocument;
        if (slot.kind === 'cite') {
            const pill = doc.createElement('a');
            pill.className = 'lg-chat-cite';
            pill.setAttribute('href', slot.href);
            pill.setAttribute('data-cite', slot.cite);
            // The label names what the reader sees: the text as parsed, never the markup that produced it.
            pill.setAttribute('aria-label', context.citeLabel((placeholder.textContent ?? '').trim(), slot.title));
            pill.append(...placeholder.childNodes);
            placeholder.replaceWith(pill);
        } else {
            const mark = doc.createElement('span');
            mark.className = 'lg-chat-unverified';
            mark.tabIndex = 0;
            mark.setAttribute('data-unverified', slot.id);
            const hidden = doc.createElement('span');
            hidden.className = 'sr-only';
            hidden.textContent = ` ${context.unverifiedWord()}`;
            mark.append(slot.id, doc.importNode(glyph, true), hidden);
            placeholder.replaceWith(mark);
        }
    }
}

/**
 * The answer's Markdown as sanitised HTML, with its citations turned into the chat's own marks.
 *
 * <p>**Raw HTML is text.** Markdown passes raw HTML through by design, and the answer comes from a
 * model that read scraped adverts. Passed through, one open attribute quote in an HTML block was
 * enough for the parser to swallow the next pill's attributes into an attacker's anchor (fix 3F-1).
 * So `marked`'s `html` renderer escapes it, block and inline, and its `text` renderer escapes the
 * text after an inline `<code>` or `<pre>` too, which marked would otherwise pass through as raw:
 * the reader sees the markup, nothing parses it.
 *
 * <p>**Nothing read back from parsed HTML navigates.** The renderer writes a pill or a mark as a bare
 * placeholder carrying one slot key; DOMPurify runs over everything and strips every `href`; then
 * the placeholders are looked up in this render's own map and rebuilt from it — tag checked, each
 * slot once. The URL, the `data-cite` and the label come from the map, which only the server's
 * citations fill. A Markdown link is its text plus the shortened address as plain text; an image is
 * its alt text. `nginx.conf` restricts `img-src` as a further line.
 *
 * <p>**The browser decides nothing about grounding.** A `[n](cite:…)` link is one the server
 * checked against what the turn's tools returned; `⟨unverified:ID⟩` is one it did not. The first
 * is a numbered pill, the second plain text under a dashed warning line with a shield-question
 * glyph and a hidden word, never a link.
 *
 * <p>One parser per component, because its renderer closes over that component's context.
 */
export function answerRenderer(context: AnswerContext): (markdown: string) => string {
    const parser = new Marked({gfm: true, breaks: true});
    // Drawn fresh for every render, with a fresh map; both are empty between renders.
    let mint = '';
    let slots: Slot[] = [];
    const slot = (value: Slot): string => {
        slots.push(value);
        return `${SLOT}="${mint}.${slots.length - 1}"`;
    };
    const glyph = iconSvg('shield-question', 14);
    // Its own instance, so the hook disarms this renderer's output and nobody else's.
    const purify = DOMPurify(window);
    purify.addHook('afterSanitizeAttributes', disarm);
    parser.use({
        extensions: [
            {
                name: 'unverified',
                level: 'inline',
                start: (src: string) => {
                    const at = src.indexOf('⟨unverified:');
                    return at < 0 ? undefined : at;
                },
                tokenizer(src: string) {
                    const match = UNVERIFIED.exec(src);
                    return match === null ? undefined : {type: 'unverified', raw: match[0], id: match[1]};
                },
                renderer(token) {
                    return `<span ${slot({kind: 'unverified', id: String(token['id'])})}></span>`;
                },
            },
        ],
        renderer: {
            // A tag named `code`, `kbd`, `pre` or `script` sets the lexer's `inRawBlock`, and every
            // inline text after it arrives marked `escaped`, which the default renderer takes to mean
            // "already HTML" and emits as it stands — so `<dialog/open>`, which is no tag to marked,
            // became a live element (fix 4F-11). Nothing here is ever already HTML: the flag is
            // cleared and the default renders the text, escaped once and leaving an entity as it is.
            text(token: Tokens.Text | Tokens.Escape) {
                if ('escaped' in token) token.escaped = false;
                return false;
            },
            html({text, block}: Tokens.HTML | Tokens.Tag) {
                const shown = escapeHtml(text);
                return block ? `<p>${shown.trim()}</p>\n` : shown;
            },
            image({text}: Tokens.Image) {
                return escapeHtml(text);
            },
            link(this: {parser: {parseInline(tokens: Tokens.Generic[]): string}}, {href, tokens}: Tokens.Link) {
                const text = this.parser.parseInline(tokens);
                const cite = parseCite(href);
                if (cite === null) {
                    if (FOREIGN_SCHEME.test(href)) return text;
                    // Never an anchor: the address is shown so the reader can judge it, and it goes nowhere.
                    const shown = escapeHtml(shownUrl(href));
                    const plain = text.replace(/<[^>]*>/g, '').trim();
                    return plain === escapeHtml(href.trim()) || plain === href.trim() ? shown : `${text} (${shown})`;
                }
                const pill = slot({
                    kind: 'cite',
                    href: context.href(cite),
                    cite: `${cite.kind}/${cite.id}`,
                    title: context.source(cite)?.title ?? null,
                });
                return `<a ${pill}>${text}</a>`;
            },
        },
    });

    return (markdown: string) => {
        const source = markdown.trim();
        if (source === '') return '';
        mint = freshMint();
        slots = [];
        try {
            const html = parser.parse(source, {async: false}) as string;
            const fragment = purify.sanitize(html, {...SANITIZE, RETURN_DOM_FRAGMENT: true});
            fill(fragment, mint, slots, context, glyph);
            const box = document.createElement('div');
            box.append(fragment);
            return box.innerHTML;
        } finally {
            mint = '';
            slots = [];
        }
    };
}

/** Builds one component's renderer; a seam so a spec can count how often an answer is parsed. */
export type AnswerRendererFactory = (context: AnswerContext) => (markdown: string) => string;

/** The renderer factory the answer component uses: `answerRenderer`, unless a spec wraps it. */
export const ANSWER_RENDERER = new InjectionToken<AnswerRendererFactory>('lg.answerRenderer', {
    providedIn: 'root',
    factory: () => answerRenderer,
});

/** The rows an answer rests on, each once, in citation order: a reload can repeat a row two tools returned. */
export function distinctSources(sources: readonly ChatSource[]): ChatSource[] {
    const seen = new Set<string>();
    return [...sources]
        .sort((a, b) => a.n - b.n)
        .filter((s) => {
            const key = `${s.kind}/${s.id}`;
            if (seen.has(key)) return false;
            seen.add(key);
            return true;
        });
}
