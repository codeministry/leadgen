/**
 * The arithmetic the colour guards need: OKLCH to sRGB, sRGB to OKLab, the perceptual
 * distance between two colours, and the WCAG contrast ratio.
 *
 * <p>Pure functions over numbers, no DOM. The unit-tier guard (`theme-colors.spec.ts`)
 * reads `styles.css` from disk and has no browser to ask, so it converts by hand; the
 * browser tier lets a canvas do the conversion and only borrows the ratio from here. The
 * matrices are Björn Ottosson's published OKLab constants; a value that leaves the sRGB
 * gamut comes back with `inGamut: false` rather than clamped, because the theme's rule is
 * that every value has a hex twin and a clamped value has none.
 */

export interface Rgb {
    r: number;
    g: number;
    b: number;
}

export interface Lab {
    l: number;
    a: number;
    b: number;
}

export interface Srgb extends Rgb {
    /** True when every linear channel lies inside [0, 1], with a hair of tolerance. */
    inGamut: boolean;
}

const GAMUT_TOLERANCE = 0.0005;

/** `oklch(48.78% 0.0805 191.43)` → its OKLab coordinates, L on 0..1. */
export function parseOklch(text: string): Lab {
    const match = /oklch\(\s*([\d.]+)(%?)\s+([\d.]+)\s+([\d.]+)/.exec(text);
    if (!match) {
        throw new Error(`not an oklch() value: ${text}`);
    }
    const l = Number(match[1]) / (match[2] === '%' ? 100 : 1);
    const c = Number(match[3]);
    const h = (Number(match[4]) * Math.PI) / 180;
    return {l, a: c * Math.cos(h), b: c * Math.sin(h)};
}

/** OKLab → sRGB on 0..255 per channel, with the gamut verdict beside it. */
export function oklabToSrgb({l, a, b}: Lab): Srgb {
    const l_ = l + 0.3963377774 * a + 0.2158037573 * b;
    const m_ = l - 0.1055613458 * a - 0.0638541728 * b;
    const s_ = l - 0.0894841775 * a - 1.291485548 * b;
    const l3 = l_ ** 3;
    const m3 = m_ ** 3;
    const s3 = s_ ** 3;
    const linear = [
        4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
        -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
        -0.0041960863 * l3 - 0.7034186147 * m3 + 1.707614701 * s3,
    ];
    const inGamut = linear.every(v => v >= -GAMUT_TOLERANCE && v <= 1 + GAMUT_TOLERANCE);
    const [r, g, bb] = linear.map(v => Math.round(gamma(Math.min(1, Math.max(0, v))) * 255));
    return {r, g, b: bb, inGamut};
}

/** `#0E6E6B` → 0..255 per channel. */
export function parseHex(text: string): Rgb {
    const match = /^#([0-9a-f]{6})$/i.exec(text.trim());
    if (!match) {
        throw new Error(`not a six-digit hex colour: ${text}`);
    }
    const n = parseInt(match[1], 16);
    return {r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255};
}

export function toHex({r, g, b}: Rgb): string {
    return `#${[r, g, b].map(v => v.toString(16).padStart(2, '0')).join('')}`.toUpperCase();
}

/** sRGB on 0..255 → OKLab, the inverse of `oklabToSrgb` without the gamut question. */
export function srgbToOklab({r, g, b}: Rgb): Lab {
    const [lr, lg, lb] = [r, g, b].map(v => degamma(v / 255));
    const l = Math.cbrt(0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb);
    const m = Math.cbrt(0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb);
    const s = Math.cbrt(0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb);
    return {
        l: 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
        a: 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
        b: 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
    };
}

/** The Euclidean distance in OKLab, which is what "ΔE-OK" means. */
export function deltaEOk(x: Lab, y: Lab): number {
    return Math.hypot(x.l - y.l, x.a - y.a, x.b - y.b);
}

/** The hue of an OKLab colour in degrees on 0..360, and its chroma. */
export function polar({a, b}: Lab): {c: number; h: number} {
    const h = (Math.atan2(b, a) * 180) / Math.PI;
    return {c: Math.hypot(a, b), h: h < 0 ? h + 360 : h};
}

/** The smaller angle between two hues, on 0..180. */
export function hueDistance(h1: number, h2: number): number {
    const d = Math.abs(h1 - h2) % 360;
    return d > 180 ? 360 - d : d;
}

/** WCAG 2 relative luminance of an sRGB colour on 0..255. */
export function luminance({r, g, b}: Rgb): number {
    const [lr, lg, lb] = [r, g, b].map(v => degamma(v / 255));
    return 0.2126 * lr + 0.7152 * lg + 0.0722 * lb;
}

/** WCAG 2 contrast ratio, always ≥ 1, the lighter colour on top. */
export function contrastRatio(x: Rgb, y: Rgb): number {
    const a = luminance(x) + 0.05;
    const b = luminance(y) + 0.05;
    return a > b ? a / b : b / a;
}

/** Alpha-composite `top` over `ground`, both on 0..255, `alpha` on 0..1. */
export function composite(top: Rgb, alpha: number, ground: Rgb): Rgb {
    const mix = (t: number, g: number) => Math.round(t * alpha + g * (1 - alpha));
    return {r: mix(top.r, ground.r), g: mix(top.g, ground.g), b: mix(top.b, ground.b)};
}

function gamma(v: number): number {
    return v <= 0.0031308 ? 12.92 * v : 1.055 * v ** (1 / 2.4) - 0.055;
}

function degamma(v: number): number {
    return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
}
