import {FunnelStage} from '@shared/funnel-rail/funnel-stage';

/** One dot of the drawing, in viewBox units. */
export interface SieveDot {
    readonly x: number;
    readonly y: number;
}

/** One ring of the sieve: the offers one stage held back, scattered in its band. */
export interface SieveBand {
    readonly id: string;
    readonly label: string;
    readonly removed: number;
    /** The band's inner and outer radius. */
    readonly inner: number;
    readonly outer: number;
    readonly dots: readonly SieveDot[];
}

/** The whole drawing: the bands from the outside in, the core, and what one dot stands for. */
export interface SieveLayout {
    readonly bands: readonly SieveBand[];
    /** The survivors, the strong ones first so they sit at the centre. */
    readonly core: readonly SieveDot[];
    /** How many of the core's dots, counted from the centre, stand for strong matches. */
    readonly strongDots: number;
    /** Offers per dot: 1 until the archive outgrows the drawing. */
    readonly unit: number;
}

/** The viewBox is 200 × 200 around this centre. */
export const SIEVE_CENTRE = 100;
/** The brand ring around the whole: the logo's open circle, the lead in its gap. */
export const SIEVE_RING = 88;
const BANDS_OUTER = 78;
const CORE_RADIUS = 28;
/** Dots never touch a band edge or each other: the pitch one dot needs, in viewBox units. */
const PITCH = 3.7;
const EDGE = 1.6;
/** Beyond this many dots the drawing stops reading as dots; one dot then stands for several. */
const MAX_DOTS = 380;

/** The R2 sequence's two constants: the most even scatter a sequence gives without a random seed. */
const R2_A = 0.7548776662466927;
const R2_B = 0.5698402909980532;
const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));

const round = (value: number) => Math.round(value * 100) / 100;
const frac = (value: number) => value - Math.floor(value);

/** How many dots fit in an annulus at the dot pitch, a little under the packing limit. */
function capacity(inner: number, outer: number): number {
    return Math.max(1, Math.floor((Math.PI * (outer * outer - inner * inner)) / (PITCH * PITCH) * 0.8));
}

/** Offers to dots: rounded, but never zero for a count that is not zero. */
function dotsFor(count: number, unit: number): number {
    return count <= 0 ? 0 : Math.max(1, Math.round(count / unit));
}

/**
 * The sieve as geometry: every offer in the archive as a dot, the ones a stage held back in
 * that stage's ring, the first stage outermost, the survivors in the core.
 *
 * <p>Deterministic on purpose. The scatter is a low-discrepancy sequence seeded by the band's
 * index, so the same funnel draws the same picture on every load and in every screenshot, and
 * a change in the drawing always means a change in the numbers.
 */
export function layoutSieve(stages: readonly FunnelStage[], survived: number, strong: number): SieveLayout {
    const width = stages.length === 0 ? 0 : (BANDS_OUTER - CORE_RADIUS - 2) / stages.length;
    const rings = stages.map((stage, index) => ({
        stage,
        outer: BANDS_OUTER - index * width,
        inner: BANDS_OUTER - (index + 1) * width,
    }));
    const total = survived + stages.reduce((sum, stage) => sum + stage.removed, 0);
    const unit = Math.max(
        1,
        Math.ceil(total / MAX_DOTS),
        Math.ceil(survived / capacity(0, CORE_RADIUS)),
        ...rings.map((ring) => Math.ceil(ring.stage.removed / capacity(ring.inner, ring.outer))),
    );

    const bands = rings.map<SieveBand>((ring, index) => {
        const count = dotsFor(ring.stage.removed, unit);
        const from = (ring.inner + EDGE) ** 2;
        const to = (ring.outer - EDGE) ** 2;
        const seed = index * 0.137;
        const dots = Array.from({length: count}, (_, k) => {
            const angle = 2 * Math.PI * frac(seed + k * R2_A);
            const radius = Math.sqrt(from + frac(seed * 3 + k * R2_B) * (to - from));
            return {x: round(SIEVE_CENTRE + radius * Math.cos(angle)), y: round(SIEVE_CENTRE + radius * Math.sin(angle))};
        });
        return {id: ring.stage.id, label: ring.stage.label, removed: ring.stage.removed, inner: round(ring.inner), outer: round(ring.outer), dots};
    });

    // The core is a sunflower: dot k at the golden angle and at a radius growing with √k,
    // which packs a disc evenly and puts the first dots, the strong matches, at the centre.
    const coreCount = dotsFor(survived, unit);
    const spread = coreCount === 0 ? 0 : (CORE_RADIUS - EDGE) / Math.sqrt(coreCount);
    const core = Array.from({length: coreCount}, (_, k) => {
        const radius = spread * Math.sqrt(k + 0.5);
        const angle = k * GOLDEN_ANGLE;
        return {x: round(SIEVE_CENTRE + radius * Math.cos(angle)), y: round(SIEVE_CENTRE + radius * Math.sin(angle))};
    });

    return {bands, core, strongDots: Math.min(coreCount, dotsFor(Math.min(strong, survived), unit)), unit};
}
