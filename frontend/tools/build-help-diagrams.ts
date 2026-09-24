/**
 * Renders every help diagram from its Mermaid source into one SVG that wears the app's theme.
 *
 *   bun run help:diagrams
 *
 * Reads `src/help/diagrams/<id>.mmd` and writes `public/help/diagrams/<id>.svg`. The drawer
 * inlines that file into the page, so the SVG's colours can be the app's CSS variables and the
 * one file follows the light and the dark theme, and its text takes the page's font.
 *
 * How the colours get there: Mermaid only accepts colours it can compute with, not `var(…)`.
 * So each semantic role (node, edge, cluster, the AI step, …) is rendered in a placeholder
 * colour that means nothing but "this role", and afterwards every placeholder, in each spelling
 * Mermaid emits it (hex either case, `rgb()`), is replaced by the token in `ROLES`. The
 * `classDef` colours written in a `.mmd` are replaced the same way, by class name, through
 * `CLASSES` — they are there so the source still previews on its own. Anything the map does
 * not cover fails the build with the leftover literals listed, rather than shipping a colour
 * that is the same in both themes.
 *
 * Text is plain SVG `<text>` (`htmlLabels: false`, no `<foreignObject>`), and every
 * `font-family` Mermaid writes is removed, so the labels inherit `var(--font-sans)`.
 *
 * Each file opens with `<!-- source-sha256: <hash> -->`, the sha256 of the `.mmd` followed by
 * THIS SCRIPT. The script is hashed because it carries the token map: a changed role or class
 * mapping makes every diagram stale exactly as a changed source does. `help-diagrams.spec.ts`
 * recomputes the hash and fails on a mismatch, so neither can ship without a re-render.
 *
 * Mermaid never reaches the browser bundle: mermaid-cli runs through bunx and is not a
 * dependency. It drives a headless Chrome through puppeteer. bunx does not run puppeteer's
 * install script, so point it at an installed Chrome when the launch fails:
 *
 *   PUPPETEER_EXECUTABLE_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" bun run help:diagrams
 *
 * The version is pinned because a different Mermaid lays the same source out differently,
 * and a re-render should only ever change when the source did.
 */
import {createHash} from 'node:crypto';
import {mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';

const MERMAID_CLI = '@mermaid-js/mermaid-cli@11.17.0';
const FRONTEND = resolve(import.meta.dir, '..');
const SOURCES = join(FRONTEND, 'src/help/diagrams');
const OUTPUT = join(FRONTEND, 'public/help/diagrams');
const SCRIPT = import.meta.path;

/**
 * One placeholder per semantic role, and the app token it becomes. Placeholders are unique
 * and deliberately odd, so none can be mistaken for a colour Mermaid computed on its own.
 * Existing tokens only: `styles.css` and `tokens.css` define each of them for both themes.
 */
const ROLES = {
    nodeFill: {placeholder: '#a10001', token: '--color-base-100'},
    nodeBorder: {placeholder: '#a10002', token: '--lg-muted'},
    text: {placeholder: '#a10003', token: '--color-base-content'},
    line: {placeholder: '#a10004', token: '--lg-muted'},
    clusterFill: {placeholder: '#a10005', token: '--color-base-200'},
    clusterBorder: {placeholder: '#a10006', token: '--color-base-300'},
    aiFill: {placeholder: '#a10007', token: '--lg-ai-surface'},
    aiBorder: {placeholder: '#a10008', token: '--lg-ai'},
    netFill: {placeholder: '#a10009', token: '--lg-selected-surface'},
    netBorder: {placeholder: '#a1000a', token: '--color-primary'},
    noteFill: {placeholder: '#a1000b', token: '--color-base-200'},
    fileBorder: {placeholder: '#a1000c', token: '--color-base-content'},
    goodBorder: {placeholder: '#a1000d', token: '--color-success'},
} as const;
type Role = keyof typeof ROLES;
const p = (role: Role) => ROLES[role].placeholder;

/** The `classDef` names the sources use, and the roles each one is drawn in. */
const CLASSES: Record<string, {fill: Role; stroke: Role}> = {
    model: {fill: 'aiFill', stroke: 'aiBorder'}, // a language model works in this step
    net: {fill: 'netFill', stroke: 'netBorder'}, // this step leaves the machine
    free: {fill: 'nodeFill', stroke: 'nodeBorder'}, // rules only
    file: {fill: 'nodeFill', stroke: 'fileBorder'}, // a file on disk
    good: {fill: 'nodeFill', stroke: 'goodBorder'}, // the goal
};

/**
 * The family `--font-sans` starts with, loaded into the renderer's page from the same self-hosted
 * file the app ships, so Mermaid sizes every box for the font the label is finally drawn in.
 * The name is only used for measuring; the SVG itself carries no font-family.
 */
const MEASURE_FAMILY = 'Instrument Sans Variable';
const MEASURE_FONT = join(FRONTEND, 'node_modules/@fontsource-variable/instrument-sans/files/instrument-sans-latin-wght-normal.woff2');

const CONFIG = {
    theme: 'base',
    htmlLabels: false,
    flowchart: {htmlLabels: false},
    themeVariables: {
        darkMode: false,
        fontFamily: `'${MEASURE_FAMILY}', sans-serif`,
        background: p('nodeFill'),
        mainBkg: p('nodeFill'),
        primaryColor: p('nodeFill'),
        primaryBorderColor: p('nodeBorder'),
        nodeBorder: p('nodeBorder'),
        primaryTextColor: p('text'),
        nodeTextColor: p('text'),
        textColor: p('text'),
        titleColor: p('text'),
        secondaryTextColor: p('text'),
        tertiaryTextColor: p('text'),
        lineColor: p('line'),
        defaultLinkColor: p('line'),
        arrowheadColor: p('line'),
        secondaryColor: p('clusterFill'),
        tertiaryColor: p('clusterFill'),
        clusterBkg: p('clusterFill'),
        secondaryBorderColor: p('clusterBorder'),
        tertiaryBorderColor: p('clusterBorder'),
        clusterBorder: p('clusterBorder'),
        edgeLabelBackground: p('nodeFill'),
        noteBkgColor: p('noteFill'),
        noteBorderColor: p('clusterBorder'),
        noteTextColor: p('text'),
    },
};

/** The sha256 of the source and this script, which is where the token map lives. */
function stampOf(source: string): string {
    return createHash('sha256').update(readFileSync(source)).update(readFileSync(SCRIPT)).digest('hex');
}

/** Rewrites every `classDef` line's colours to the placeholders of its roles. */
function withPlaceholderClasses(mmd: string, source: string): string {
    return mmd.replace(/^(\s*classDef\s+)(\w+)\s+.*$/gm, (_line, head: string, name: string) => {
        const roles = CLASSES[name];
        if (roles === undefined) {
            throw new Error(`${source}: classDef "${name}" has no roles in CLASSES — add it there`);
        }
        return `${head}${name} fill:${p(roles.fill)},stroke:${p(roles.stroke)},color:${p('text')}`;
    });
}

function hexToRgb(hex: string): string {
    const [r, g, b] = [1, 3, 5].map((i) => Number.parseInt(hex.slice(i, i + 2), 16));
    return `rgb\\(\\s*${r}\\s*,\\s*${g}\\s*,\\s*${b}\\s*\\)`;
}

/**
 * Mermaid ships the stylesheet for every look and for KaTeX in every SVG, and two drop-shadow
 * filters nothing points at. None of it is drawn in the classic look these diagrams use, and
 * it carries fixed blacks and greys; it goes, rather than being mapped to a token it never uses.
 */
function withoutDeadStyles(svg: string): string {
    const dead = (selector: string) => /\[data-look="neo"\]|\.katex/.test(selector);
    let out = svg.replace(/<style>([\s\S]*?)<\/style>/g, (_style, css: string) => {
        const pruned = css.replace(/([^{}]+)\{([^{}]*)\}/g, (rule, selectors: string, body: string) => {
            const all = selectors.split(',');
            const live = all.filter((selector) => !dead(selector));
            if (live.length === all.length) return rule;
            return live.length === 0 ? '' : `${live.join(',')}{${body}}`;
        });
        return `<style>${pruned}</style>`;
    });
    out = out.replace(/<defs><filter id="([^"]+)"[^>]*>.*?<\/filter><\/defs>/g, (defs, id: string) =>
        out.includes(`url(#${id})`) ? defs : '',
    );
    return out;
}

/** Placeholders to tokens, Mermaid's fonts and backgrounds out, and what is left asserted. */
function themed(svg: string, id: string): string {
    let out = withoutDeadStyles(svg.replace(/<\?xml[^>]*\?>\s*/g, ''));
    for (const {placeholder, token} of Object.values(ROLES)) {
        const spelled = new RegExp(`${placeholder}\\b|${hexToRgb(placeholder)}`, 'gi');
        out = out.replace(spelled, `var(${token})`);
    }
    out = out
        // Declarations inside <style> and style="…" (the value may quote a family name), and
        // the attribute form.
        .replace(/font-family\s*:(?:\s*"[^"]*"|\s*'[^']*'|[^;}"'])*;?/gi, '')
        .replace(/\sfont-family="[^"]*"/gi, '')
        // The page's ground shows through; the drawer's figure owns the background.
        .replace(/background-color\s*:\s*[^;}"]*;?/gi, '');

    const leftovers = [...new Set(out.match(/#[0-9a-f]{3,8}\b|\b(?:rgba?|hsla?)\([^)]*\)/gi) ?? [])];
    if (leftovers.length > 0) {
        throw new Error(`${id}.svg still carries colour literals no role maps: ${leftovers.join(', ')}`);
    }
    return out;
}

function render(source: string, config: string, css: string, id: string, target: string): void {
    const result = Bun.spawnSync(
        ['bunx', '-p', MERMAID_CLI, 'mmdc', '-i', source, '-o', target, '-c', config, '-C', css,
            '-I', `help-diagram-${id}`, '-b', 'transparent', '-q'],
        {stdout: 'inherit', stderr: 'inherit'},
    );
    if (result.exitCode !== 0) {
        console.error(`mmdc failed for ${source}. If Chrome did not launch, set PUPPETEER_EXECUTABLE_PATH.`);
        process.exit(result.exitCode ?? 1);
    }
}

const ids = readdirSync(SOURCES)
    .filter((name) => name.endsWith('.mmd'))
    .map((name) => name.slice(0, -'.mmd'.length))
    .sort();
mkdirSync(OUTPUT, {recursive: true});
const scratch = mkdtempSync(join(tmpdir(), 'help-diagrams-'));

try {
    const config = join(scratch, 'mermaid.json');
    writeFileSync(config, JSON.stringify(CONFIG));
    const css = join(scratch, 'measure.css');
    const font = readFileSync(MEASURE_FONT).toString('base64');
    writeFileSync(css, `@font-face{font-family:'${MEASURE_FAMILY}';font-weight:100 900;` +
        `src:url(data:font/woff2;base64,${font}) format('woff2');}`);
    // One file per source: whatever else is in the folder (the old per-theme pair) goes.
    for (const name of readdirSync(OUTPUT)) {
        if (!ids.some((id) => name === `${id}.svg`)) rmSync(join(OUTPUT, name));
    }
    for (const id of ids) {
        const source = join(SOURCES, `${id}.mmd`);
        const prepared = join(scratch, `${id}.mmd`);
        writeFileSync(prepared, withPlaceholderClasses(readFileSync(source, 'utf8'), source));
        const raw = join(scratch, `${id}.svg`);
        render(prepared, config, css, id, raw);
        const hash = stampOf(source);
        writeFileSync(join(OUTPUT, `${id}.svg`), `<!-- source-sha256: ${hash} -->\n${themed(readFileSync(raw, 'utf8'), id)}\n`);
        console.log(`${id}.svg  ${hash.slice(0, 12)}`);
    }
} finally {
    rmSync(scratch, {recursive: true, force: true});
}
