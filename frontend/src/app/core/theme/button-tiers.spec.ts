import {readdirSync, readFileSync, statSync} from 'node:fs';
import {join, resolve} from 'node:path';

/**
 * The tier guard (ISC-231): every button in a template is in exactly one tier.
 *
 * <p>Three tiers and one exemption. `btn-primary` alone is the filled call to action, one
 * per screen area. `btn-soft` (with or without a colour beside it) is the secondary tier:
 * available, not urgent. `btn-ghost` is the tertiary: close, back, clear, reveal, the square
 * icon buttons. A `join-item` is a segmented *state* selector, not an action, and is exempt.
 * `btn-outline` used to mark "filter not at default" and is retired: outline reads as a
 * tier, and a state needs its own marker.
 *
 * <p>Reads the templates from disk, because the question is about the source: a class
 * assembled at runtime is seen neither here nor by Tailwind, which is why every variant is
 * a literal in the template or in a literal map beside the component.
 */

const APP = resolve(process.cwd(), 'src/app');
const RETIRED = ['btn-outline', 'btn-link', 'btn-secondary', 'btn-accent', 'btn-neutral'] as const;

interface ButtonSite {
    file: string;
    line: number;
    classes: string[];
}

function htmlFilesUnder(dir: string): string[] {
    const found: string[] = [];
    for (const entry of readdirSync(dir)) {
        const path = join(dir, entry);
        if (statSync(path).isDirectory()) found.push(...htmlFilesUnder(path));
        else if (entry.endsWith('.html')) found.push(path);
    }
    return found;
}

/**
 * Every opening tag whose static `class` attribute names `btn`, with the classes that tag
 * carries statically or through `[class.x]` bindings.
 */
function buttonSites(): ButtonSite[] {
    const sites: ButtonSite[] = [];
    for (const file of htmlFilesUnder(APP)) {
        const text = readFileSync(file, 'utf8');
        for (const tag of text.matchAll(/<(button|a)\b[^>]*>/gs)) {
            const attrs = tag[0];
            const staticClass = /\sclass="([^"]*)"/.exec(attrs)?.[1] ?? '';
            const classes = staticClass.split(/\s+/).filter(Boolean);
            for (const bound of attrs.matchAll(/\[class\.([\w-]+)\]/g)) classes.push(bound[1]);
            if (!classes.includes('btn')) continue;
            const line = text.slice(0, tag.index).split('\n').length;
            sites.push({file: file.slice(APP.length + 1), line, classes});
        }
    }
    return sites;
}

type Tier = 'primary' | 'soft' | 'ghost' | 'selector';

/** The tiers a site could be read as; a well-formed site has exactly one. */
function tiersOf(site: ButtonSite): Tier[] {
    const has = (c: string) => site.classes.includes(c);
    // A segmented selector is a state control, whatever tier class it borrows for its look:
    // the theme and language toggles ride on `btn-ghost` inside a `join`, and that is the
    // selector's idiom, not a second tier.
    if (has('join-item')) return ['selector'];
    const tiers: Tier[] = [];
    if (has('btn-soft')) tiers.push('soft');
    else if (has('btn-primary')) tiers.push('primary');
    if (has('btn-ghost')) tiers.push('ghost');
    return tiers;
}

const where = (s: ButtonSite) => `${s.file}:${s.line} [${s.classes.join(' ')}]`;

describe('the button tiers (ISC-231)', () => {
    const sites = buttonSites();

    it('finds the buttons', () => {
        expect(sites.length).toBeGreaterThan(30);
    });

    it('puts every button in exactly one tier, or makes it a segmented selector', () => {
        const offenders = sites.filter(s => tiersOf(s).length !== 1).map(s => `${where(s)} → ${tiersOf(s).join(',') || 'none'}`);
        expect(offenders).toEqual([]);
    });

    it('uses none of the retired variants', () => {
        const offenders = sites.filter(s => RETIRED.some(r => s.classes.includes(r))).map(where);
        expect(offenders).toEqual([]);
    });

    it('keeps the primary tier rare: at most one filled button per template, dialogs aside', () => {
        // A dialog's confirm is a primary of its own; the shortlist has two dialogs and a
        // bulk bar, the saved views a save and a dialog. Everything else: one.
        const allowance: Record<string, number> = {
            'features/shortlist/shortlist-page.html': 4,
            'features/shortlist/saved-views/saved-views.html': 2,
        };
        const perFile = new Map<string, number>();
        for (const s of sites) {
            if (tiersOf(s)[0] === 'primary') perFile.set(s.file, (perFile.get(s.file) ?? 0) + 1);
        }
        const offenders = [...perFile].filter(([file, n]) => n > (allowance[file] ?? 1)).map(([file, n]) => `${file}: ${n}`);
        expect(offenders).toEqual([]);
    });
});
