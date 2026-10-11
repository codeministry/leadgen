import {TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {DashboardHero} from './dashboard-hero';

/**
 * The strong-matches line under the headline. Null means the scores have not loaded, and then the
 * line is left out; zero is an answer, and an `@if` on the value itself read it as "not loaded".
 */
describe('DashboardHero', () => {
    function render(strong: number | null): HTMLElement {
        TestBed.configureTestingModule({imports: [DashboardHero], providers: [provideRouter([])]});
        const fixture = TestBed.createComponent(DashboardHero);
        fixture.componentRef.setInput('survived', 12);
        fixture.componentRef.setInput('total', 40);
        fixture.componentRef.setInput('stages', []);
        fixture.componentRef.setInput('strong', strong);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    it('says how many of the survivors match strongly', () => {
        expect(render(3).querySelector('.strong')?.textContent?.trim()).toBe('3 of them match strongly.');
    });

    it('says so when none of them match strongly, rather than leaving the line out', () => {
        expect(render(0).querySelector('.strong')?.textContent?.trim()).toBe('0 of them match strongly.');
    });

    it('leaves the line out while the scores have not loaded', () => {
        expect(render(null).querySelector('.strong')).toBeNull();
    });
});
