import {TestBed} from '@angular/core/testing';
import {page} from 'vitest/browser';
import {IntakeSpark} from './intake-spark';
import {SparkDay} from './spark-day';

/** Fourteen days with five-digit counts and long labels: the widest table the dashboard hands in. */
const DAYS: readonly SparkDay[] = Array.from({length: 14}, (_, i) => ({
    day: `2026-09-${String(i + 10).padStart(2, '0')}`,
    extracted: 12_345 + i,
    shortlisted: 1_234 + i,
}));

async function mount(width: number): Promise<HTMLElement> {
    TestBed.configureTestingModule({imports: [IntakeSpark]});
    const frame = document.createElement('div');
    frame.style.inlineSize = `${width}px`;
    document.body.appendChild(frame);
    const fixture = TestBed.createComponent(IntakeSpark);
    fixture.componentRef.setInput('days', DAYS);
    fixture.componentRef.setInput('extractedLabel', 'extracted from the sources');
    fixture.componentRef.setInput('shortlistedLabel', 'shortlisted after the filter');
    frame.appendChild(fixture.nativeElement as HTMLElement);
    fixture.detectChanges();
    await fixture.whenStable();
    return frame;
}

describe('IntakeSpark at phone widths', () => {
    afterEach(() => document.body.querySelectorAll(':scope > div').forEach((div) => div.remove()));

    it('keeps its screen-reader table from widening a 320 px page', async () => {
        await page.viewport(320, 800);
        const frame = await mount(288);

        // The table is still there for a screen reader, all fourteen days of it.
        expect(frame.querySelectorAll('tbody tr')).toHaveLength(14);
        expect(frame.scrollWidth).toBe(frame.clientWidth);
        expect(document.documentElement.scrollWidth).toBe(document.documentElement.clientWidth);
    });
});
