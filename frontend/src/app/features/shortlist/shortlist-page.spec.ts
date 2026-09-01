import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ShortlistEntry } from '@core/model/shortlist-entry';
import { ShortlistPage } from './shortlist-page';

function entry(id: number, title: string, value: number | null, portal: string): ShortlistEntry {
  return {
    offer: {
      id,
      externalId: `https://example.invalid/${id}`,
      title,
      description: 'Ablösung eines Monolithen.',
      url: `https://example.invalid/${id}`,
      location: 'Köln',
      portal,
      agency: null,
      publishedOn: '2026-09-01',
      tags: ['Java'],
      rateEur: null,
      remotePercent: null,
      startsOn: null,
      duration: null,
      workload: null,
      language: 'de',
      fullText: null,
      packageDir: null,
    },
    score: { value, hardPass: true, reasons: [], model: null, rulesetVersion: '1' },
    flags: { incomplete: false, remoteUnknown: true },
    sources: [{ portal, agency: null, url: `https://example.invalid/${id}` }],
  };
}

const ENTRIES: readonly ShortlistEntry[] = [
  entry(1, 'Senior Java Entwickler', 88, 'FreelancerMap'),
  entry(2, 'Java Entwickler', 64, 'freelance.de'),
  entry(3, 'Angular Entwickler', null, 'FreelancerMap'),
];

describe('ShortlistPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  function render(): ComponentFixture<ShortlistPage> {
    const fixture = TestBed.createComponent(ShortlistPage);
    fixture.detectChanges();
    http.expectOne('/api/offers').flush(ENTRIES);
    fixture.detectChanges();
    return fixture;
  }

  function cards(fixture: ComponentFixture<ShortlistPage>): number {
    return fixture.nativeElement.querySelectorAll('lg-offer-card').length;
  }

  it('renders with no query parameters at all', () => {
    // Router input binding writes `undefined` for an absent parameter rather than leaving
    // the declared default, and the resulting `undefined.trim()` fails inside the
    // template — half a page, no useful console error.
    const fixture = TestBed.createComponent(ShortlistPage);
    fixture.componentRef.setInput('q', undefined);
    fixture.componentRef.setInput('band', undefined);
    fixture.componentRef.setInput('portal', undefined);
    fixture.detectChanges();
    http.expectOne('/api/offers').flush(ENTRIES);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Shortlist');
    expect(cards(fixture)).toBe(3);
  });

  it('filters down to the shortlist band', () => {
    const fixture = render();

    fixture.componentRef.setInput('band', 'shortlist');
    fixture.detectChanges();

    expect(cards(fixture)).toBe(1);
  });

  it('searches a description that is not there', () => {
    // A source that states no description is the normal case, and `undefined.toLowerCase()`
    // inside a computed leaves the page half-rendered.
    const fixture = TestBed.createComponent(ShortlistPage);
    fixture.detectChanges();
    http.expectOne('/api/offers').flush([{ ...ENTRIES[0], offer: { ...ENTRIES[0]!.offer, description: null } }]);
    fixture.detectChanges();

    fixture.componentRef.setInput('q', 'monolith');
    fixture.detectChanges();

    expect(cards(fixture)).toBe(0);
    expect(fixture.nativeElement.textContent).toContain('Shortlist');
  });

  it('offers only the portals that are actually there', () => {
    const fixture = render();

    const options: HTMLOptionElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('select option'),
    );
    expect(options.map((option) => option.value)).toEqual(['', 'FreelancerMap', 'freelance.de']);
  });
});
