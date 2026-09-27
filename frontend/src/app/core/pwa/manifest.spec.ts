import {existsSync, readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {inflateSync} from 'node:zlib';
import {THEME_SURFACE_HEX} from '@core/theme/theme.model';

/**
 * The web app manifest (ISC-327): what a browser reads before it offers to install the app,
 * and what an installed window paints before the first bundle arrives.
 *
 * <p>It reads the manifest and `index.html` from disk rather than through a browser, because
 * the questions are about the files: is the manifest linked at all, does it open on `/` in its
 * own window, and are its two colours the dark surface the stylesheet pins. A manifest that
 * names the wrong colour is not an error anywhere; the installed window flashes white and then
 * corrects itself, which is the failure this spec turns into a red line.
 *
 * <p>The icons it names are held to their files here as well (ISC-328): each one exists in
 * `public/`, is the size the manifest states, and has the plate its purpose needs. A missing or
 * wrongly sized icon is not an error anywhere either; Chrome silently declines to offer the
 * install, and Android draws a white square behind a maskable icon with transparent corners.
 * The check reads the PNG header and unfilters the rows itself, which needs no image library,
 * and for the maskable icon it measures how far the mark reaches from the centre: Android's
 * circle mask keeps only the central 80 %, and the farthest part of the mark is the outer
 * signal dot, not the ring — a mark sized by its ring alone loses that dot to the mask.
 */

const FRONTEND = process.cwd();
const MANIFEST = resolve(FRONTEND, 'public/manifest.webmanifest');
const INDEX = resolve(FRONTEND, 'src/index.html');
const BUILD_SCRIPT = resolve(FRONTEND, 'tools/build-favicon.sh');
const TOUCH_ICON = 'apple-touch-icon.png';
const TOUCH_ICON_SIZE = 180;

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const PNG_TRUECOLOUR = 2;
const PNG_TRUECOLOUR_ALPHA = 6;

interface Png {
    width: number;
    height: number;
    bitDepth: number;
    colorType: number;
    interlace: number;
    /** Channels per pixel: 3 for colour type 2, 4 for colour type 6. */
    channels: number;
    /** The top-left pixel's channels: RGB for colour type 2, RGBA for colour type 6. */
    topLeft: number[];
    /** Every row unfiltered, `width * channels` bytes each, top row first. */
    pixels: Buffer;
}

/** Undoes the five PNG row filters (RFC 2083 § 6), one scanline at a time. */
function unfilter(raw: Buffer, width: number, height: number, bpp: number): Buffer {
    const stride = width * bpp;
    const out = Buffer.alloc(stride * height);
    for (let y = 0; y < height; y++) {
        const filter = raw[y * (stride + 1)];
        const from = y * (stride + 1) + 1;
        const to = y * stride;
        for (let x = 0; x < stride; x++) {
            const left = x >= bpp ? out[to + x - bpp] : 0;
            const up = y > 0 ? out[to - stride + x] : 0;
            const upLeft = y > 0 && x >= bpp ? out[to - stride + x - bpp] : 0;
            let predicted: number;
            switch (filter) {
                case 0:
                    predicted = 0;
                    break;
                case 1:
                    predicted = left;
                    break;
                case 2:
                    predicted = up;
                    break;
                case 3:
                    predicted = (left + up) >> 1;
                    break;
                case 4: {
                    const p = left + up - upLeft;
                    const pa = Math.abs(p - left);
                    const pb = Math.abs(p - up);
                    const pc = Math.abs(p - upLeft);
                    predicted = pa <= pb && pa <= pc ? left : pb <= pc ? up : upLeft;
                    break;
                }
                default:
                    throw new Error(`row ${y}: unknown PNG filter ${filter}`);
            }
            out[to + x] = (raw[from + x] + predicted) & 0xff;
        }
    }
    return out;
}

/**
 * The farthest any pixel that is not the plate lies from the image centre, in pixels: the
 * mark's true extent, whichever part of the drawing reaches the furthest.
 */
function farthestFromCentre(png: Png, plate: number[]): number {
    const cx = (png.width - 1) / 2;
    const cy = (png.height - 1) / 2;
    let farthest = 0;
    for (let y = 0; y < png.height; y++) {
        for (let x = 0; x < png.width; x++) {
            const at = (y * png.width + x) * png.channels;
            const isPlate = plate.every((channel, i) => png.pixels[at + i] === channel);
            if (!isPlate) farthest = Math.max(farthest, Math.hypot(x - cx, y - cy));
        }
    }
    return farthest;
}

/** Parses the PNG header and unfilters every row; throws on anything that is not a PNG. */
function readPng(file: string): Png {
    const bytes = readFileSync(file);
    if (!bytes.subarray(0, 8).equals(PNG_SIGNATURE)) throw new Error(`${file} is not a PNG`);
    let header: Omit<Png, 'channels' | 'topLeft' | 'pixels'> | undefined;
    const idat: Buffer[] = [];
    for (let at = 8; at + 8 <= bytes.length; ) {
        const length = bytes.readUInt32BE(at);
        const type = bytes.toString('latin1', at + 4, at + 8);
        const data = bytes.subarray(at + 8, at + 8 + length);
        if (type === 'IHDR') {
            header = {
                width: data.readUInt32BE(0),
                height: data.readUInt32BE(4),
                bitDepth: data[8],
                colorType: data[9],
                interlace: data[12],
            };
        } else if (type === 'IDAT') {
            idat.push(data);
        } else if (type === 'IEND') {
            break;
        }
        at += 12 + length;
    }
    if (!header) throw new Error(`${file} has no IHDR`);
    const channels = header.colorType === PNG_TRUECOLOUR_ALPHA ? 4 : header.colorType === PNG_TRUECOLOUR ? 3 : 0;
    if (channels === 0 || header.bitDepth !== 8 || header.interlace !== 0) {
        throw new Error(`${file}: only 8-bit non-interlaced truecolour is read here`);
    }
    const pixels = unfilter(inflateSync(Buffer.concat(idat)), header.width, header.height, channels);
    return {...header, channels, topLeft: [...pixels.subarray(0, channels)], pixels};
}

function rgbOf(hex: string): number[] {
    return [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
}

interface ManifestIcon {
    src: string;
    sizes: string;
    type: string;
    purpose: string;
}

interface Manifest {
    name: string;
    short_name: string;
    description: string;
    lang: string;
    id: string;
    start_url: string;
    scope: string;
    display: string;
    theme_color: string;
    background_color: string;
    icons: ManifestIcon[];
}

/** One tag of the head, whichever way its attributes are ordered and whether or not it self-closes. */
function headTag(html: string, tag: string, attribute: string, value: string): string | undefined {
    const re = new RegExp(`<${tag}\\b[^>]*\\b${attribute}="${value}"[^>]*>`, 'g');
    return html.match(re)?.[0];
}

function attributeOf(tag: string, name: string): string | undefined {
    return new RegExp(`\\b${name}="([^"]*)"`).exec(tag)?.[1];
}

describe('the web app manifest (ISC-327)', () => {
    const manifest = JSON.parse(readFileSync(MANIFEST, 'utf8')) as Manifest;

    it('names the app in English, the same words as the page title', () => {
        expect(manifest.name).toBe('Lead Generation');
        expect(manifest.short_name).toBe('Leadgen');
        expect(manifest.lang).toBe('en');
        expect(manifest.description.length).toBeGreaterThan(0);
    });

    it('opens on the root, in its own window', () => {
        expect(manifest.id).toBe('/');
        expect(manifest.start_url).toBe('/');
        expect(manifest.scope).toBe('/');
        expect(manifest.display).toBe('standalone');
    });

    it('paints the dark surface before the first bundle arrives', () => {
        const dark = THEME_SURFACE_HEX['lg-dark'];
        expect(manifest.theme_color).toBe(dark);
        expect(manifest.background_color).toBe(dark);
    });

    it('names the three icons the build script renders, with their sizes and purposes', () => {
        const named = manifest.icons.map(({src, sizes, purpose}) => [src, sizes, purpose]);
        expect(named).toEqual([
            ['icon-192.png', '192x192', 'any'],
            ['icon-512.png', '512x512', 'any'],
            ['icon-512-maskable.png', '512x512', 'maskable'],
        ]);
        for (const icon of manifest.icons) expect(icon.type, icon.src).toBe('image/png');
    });

    it('names no configured value', () => {
        const text = readFileSync(MANIFEST, 'utf8');
        expect(text).not.toMatch(/\$\{/);
        expect(text).not.toMatch(/https?:/i);
    });
});

describe('the icons the manifest names (ISC-328)', () => {
    const manifest = JSON.parse(readFileSync(MANIFEST, 'utf8')) as Manifest;
    const script = readFileSync(BUILD_SCRIPT, 'utf8');
    const plate = rgbOf(THEME_SURFACE_HEX['lg-dark']);
    const named = [...manifest.icons, {src: TOUCH_ICON, sizes: `${TOUCH_ICON_SIZE}x${TOUCH_ICON_SIZE}`, purpose: 'touch'}];

    it.each(named)('$src is named as an output of build-favicon.sh', ({src}) => {
        expect(script).toContain(`public/${src}`);
    });

    it.each(named)('$src exists in public/ at $sizes', ({src, sizes}) => {
        const file = resolve(FRONTEND, 'public', src);
        expect(existsSync(file), `${src} missing — run tools/build-favicon.sh`).toBe(true);
        const [width, height] = sizes.split('x').map(Number);
        const png = readPng(file);
        expect([png.width, png.height]).toEqual([width, height]);
        expect(png.bitDepth).toBe(8);
        expect(png.interlace).toBe(0);
    });

    it.each(named.filter(({purpose}) => purpose === 'any'))(
        '$src sits on the round plate, with transparent corners',
        ({src}) => {
            const png = readPng(resolve(FRONTEND, 'public', src));
            expect(png.colorType).toBe(PNG_TRUECOLOUR_ALPHA);
            expect(png.topLeft[3], 'top-left alpha').toBe(0);
        },
    );

    it.each(named.filter(({purpose}) => purpose !== 'any'))(
        '$src sits on an opaque square plate in the dark surface',
        ({src}) => {
            const png = readPng(resolve(FRONTEND, 'public', src));
            expect(png.colorType, 'no alpha channel').toBe(PNG_TRUECOLOUR);
            expect(png.topLeft).toEqual(plate);
        },
    );

    // The safe zone is the central circle of 80 % of the width: everything outside it may be
    // masked away by the launcher, and the mark's outer dot is what reaches the farthest.
    it.each(named.filter(({purpose}) => purpose !== 'any'))(
        "$src keeps every pixel of the mark inside the launcher's safe zone",
        ({src}) => {
            const png = readPng(resolve(FRONTEND, 'public', src));
            const safeRadius = 0.4 * png.width;
            expect(farthestFromCentre(png, plate), `farthest mark pixel, safe radius ${safeRadius}`).toBeLessThanOrEqual(
                safeRadius,
            );
        },
    );
});

describe('the head links the manifest (ISC-327)', () => {
    const html = readFileSync(INDEX, 'utf8');

    it('links the manifest by its relative path', () => {
        const link = headTag(html, 'link', 'rel', 'manifest');
        expect(link).toBeDefined();
        expect(attributeOf(link!, 'href')).toBe('manifest.webmanifest');
    });

    it('carries a theme-color for the first paint, in the dark surface', () => {
        const meta = headTag(html, 'meta', 'name', 'theme-color');
        expect(meta).toBeDefined();
        expect(attributeOf(meta!, 'content')).toBe(THEME_SURFACE_HEX['lg-dark']);
    });
});
