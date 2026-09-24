import {Component, signal} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {AnchorRail, AnchorSection} from './anchor-rail';

const SECTIONS: readonly AnchorSection[] = [
    {id: 'intake', key: 'analytics.intake'},
    {id: 'runs', key: 'analytics.runs'},
    {id: 'scores', key: 'analytics.scores'},
];

/** A screen: the rail wrapping three sections whose headings carry the ids the links name. */
@Component({
    imports: [AnchorRail],
    template: `
        <lg-anchor-rail [sections]="sections()" label="Sections">
            <h2 id="intake" tabindex="-1">Intake</h2>
            <h2 id="runs" tabindex="-1">Runs</h2>
            <h2 id="scores" tabindex="-1">Scores</h2>
        </lg-anchor-rail>
    `,
})
class Screen {
    readonly sections = signal<readonly AnchorSection[]>(SECTIONS);
}

describe('AnchorRail', () => {
    let fixture: ComponentFixture<Screen>;
    let element: HTMLElement;

    beforeEach(() => {
        TestBed.configureTestingModule({providers: [provideRouter([])]});
        fixture = TestBed.createComponent(Screen);
        fixture.detectChanges();
        element = fixture.nativeElement as HTMLElement;
    });

    it('renders a named nav with one in-page anchor per section', () => {
        const nav = element.querySelector('nav');
        expect(nav?.getAttribute('aria-label')).toBe('Sections');

        // The router writes the path in front of the hash; the hash is what the rail decides.
        const hrefs = Array.from(element.querySelectorAll('nav a'), (a) => (a.getAttribute('href') ?? '').replace(/^[^#]*/, ''));
        expect(hrefs).toEqual(['#intake', '#runs', '#scores']);
    });

    it('moves focus to the heading a link lands on, and never back to the rail', () => {
        const link = element.querySelectorAll<HTMLAnchorElement>('nav a')[1];
        link.click();
        fixture.detectChanges();

        expect(document.activeElement).toBe(element.querySelector('#runs'));
        expect(link.getAttribute('aria-current')).toBe('location');
    });

    it('renders nothing when a screen declares no sections, and keeps the content', () => {
        fixture.componentInstance.sections.set([]);
        fixture.detectChanges();

        expect(element.querySelector('nav')).toBeNull();
        expect(element.querySelector('lg-anchor-rail')?.classList.contains('bare')).toBe(true);
        expect(element.querySelectorAll('h2')).toHaveLength(3);
    });
});
