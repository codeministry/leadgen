import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ShortlistPage } from './shortlist-page';

describe('ShortlistPage', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ providers: [provideRouter([])] }).compileComponents();
  });

  it('renders with no query parameters at all', () => {
    // Router input binding writes `undefined` for an absent parameter rather than
    // leaving the declared default, and the resulting `undefined.trim()` fails
    // inside the template — half a page, no useful console error.
    const fixture = TestBed.createComponent(ShortlistPage);
    fixture.componentRef.setInput('q', undefined);
    fixture.componentRef.setInput('band', undefined);
    fixture.componentRef.setInput('portal', undefined);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Shortlist');
    expect(fixture.nativeElement.querySelectorAll('lg-offer-card').length).toBeGreaterThan(0);
  });

  it('filters down to the shortlist band', () => {
    const fixture = TestBed.createComponent(ShortlistPage);
    fixture.detectChanges();
    const all = fixture.nativeElement.querySelectorAll('lg-offer-card').length;

    fixture.componentRef.setInput('band', 'shortlist');
    fixture.detectChanges();

    const above = fixture.nativeElement.querySelectorAll('lg-offer-card').length;
    expect(above).toBeGreaterThan(0);
    expect(above).toBeLessThan(all);
  });
});
