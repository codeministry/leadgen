import {layoutSieve, SIEVE_CENTRE} from './sieve-layout';

const stages = [
    {id: 'abroad', label: 'Abroad', removed: 12},
    {id: 'remote', label: 'Remote share below the minimum', removed: 0},
    {id: 'reach', label: 'Beyond reach, not remote', removed: 17},
    {id: 'stack', label: 'Foreign stack or wrong role', removed: 45},
    {id: 'skill', label: 'No core skill', removed: 6},
    {id: 'contract', label: 'Contract form rejected', removed: 2},
];

const distance = (dot: {x: number; y: number}) => Math.hypot(dot.x - SIEVE_CENTRE, dot.y - SIEVE_CENTRE);

describe('layoutSieve', () => {
    it('draws one dot per offer while the archive fits: each stage in its ring, the survivors in the core', () => {
        const layout = layoutSieve(stages, 64, 43);

        expect(layout.unit).toBe(1);
        expect(layout.bands.map((band) => band.dots.length)).toEqual([12, 0, 17, 45, 6, 2]);
        expect(layout.core.length).toBe(64);
        expect(layout.strongDots).toBe(43);
    });

    it('puts the first stage outermost and keeps every dot inside its own band', () => {
        const layout = layoutSieve(stages, 64, 43);

        for (let i = 1; i < layout.bands.length; i++) {
            expect(layout.bands[i].outer).toBeLessThanOrEqual(layout.bands[i - 1].inner + 0.01);
        }
        for (const band of layout.bands) {
            for (const dot of band.dots) {
                expect(distance(dot)).toBeGreaterThan(band.inner);
                expect(distance(dot)).toBeLessThan(band.outer);
            }
        }
        const innermost = layout.bands.at(-1)!.inner;
        for (const dot of layout.core) {
            expect(distance(dot)).toBeLessThan(innermost);
        }
    });

    it('puts the strong matches at the centre of the core', () => {
        const layout = layoutSieve(stages, 64, 10);
        const strongest = Math.max(...layout.core.slice(0, 10).map(distance));
        const rest = Math.min(...layout.core.slice(10).map(distance));

        expect(strongest).toBeLessThanOrEqual(rest);
    });

    it('draws the same picture for the same funnel', () => {
        expect(layoutSieve(stages, 64, 43)).toEqual(layoutSieve(stages, 64, 43));
    });

    it('lets one dot stand for several offers once the archive outgrows the drawing, and never loses a stage', () => {
        const big = stages.map((stage) => ({...stage, removed: stage.removed * 40}));
        const layout = layoutSieve(big, 2560, 900);

        expect(layout.unit).toBeGreaterThan(1);
        const drawn = layout.core.length + layout.bands.reduce((sum, band) => sum + band.dots.length, 0);
        expect(drawn).toBeLessThanOrEqual(400);
        // A stage that held back anything keeps at least one dot, however small against the rest.
        expect(layout.bands.every((band) => band.removed === 0 || band.dots.length > 0)).toBe(true);
    });

    it('draws an empty core and no strong dots for a morning with no survivors', () => {
        const layout = layoutSieve(stages, 0, 0);

        expect(layout.core).toEqual([]);
        expect(layout.strongDots).toBe(0);
    });
});
