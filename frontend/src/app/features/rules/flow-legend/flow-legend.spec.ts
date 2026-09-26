import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {FlowLegend} from './flow-legend';

function render(active: ReadonlySet<string> | null): ComponentFixture<FlowLegend> {
    const fixture = TestBed.createComponent(FlowLegend);
    fixture.componentRef.setInput('active', active);
    fixture.detectChanges();
    return fixture;
}

function entries(fixture: ComponentFixture<FlowLegend>): HTMLElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('[data-marker-id]'));
}

function withClass(all: readonly HTMLElement[], cls: string): string[] {
    return all.filter((entry) => entry.classList.contains(cls)).map((entry) => entry.dataset['markerId'] ?? '');
}

/**
 * The legend answers the hovered or focused node (ISC-405): the markers that node carries are
 * highlighted, the rest faded, and every entry keeps its words either way.
 */
describe('FlowLegend', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({providers: [provideRouter([])]});
    });

    it('names every marker by an id: the four cost classes, AI, failed and the width stack', () => {
        const ids = entries(render(null)).map((entry) => entry.dataset['markerId']);

        expect(ids).toEqual(['free', 'model', 'network', 'file', 'ai', 'failed', 'width']);
    });

    it('fades and highlights nothing while no node is hovered', () => {
        const all = entries(render(null));

        expect(withClass(all, 'is-faded')).toEqual([]);
        expect(withClass(all, 'is-active')).toEqual([]);
    });

    it('highlights exactly the active markers and fades every other entry', () => {
        const all = entries(render(new Set(['network', 'ai'])));

        expect(withClass(all, 'is-active')).toEqual(['network', 'ai']);
        expect(withClass(all, 'is-faded')).toEqual(['free', 'model', 'file', 'failed', 'width']);
    });

    it('keeps every label in the DOM and readable to assistive technology while faded', () => {
        for (const entry of entries(render(new Set(['free'])))) {
            expect(entry.querySelector('.legend-label')?.textContent?.trim(), entry.dataset['markerId']).toBeTruthy();
            expect(entry.closest('[aria-hidden="true"]'), entry.dataset['markerId']).toBeNull();
        }
    });

    it('fades every entry and highlights none for a node that carries no marker', () => {
        const all = entries(render(new Set()));

        expect(withClass(all, 'is-faded')).toHaveLength(all.length);
        expect(withClass(all, 'is-active')).toEqual([]);
    });
});
