import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';

describe('App', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  it('asks the API for its status on init and renders it', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    httpMock
      .expectOne('/api/status')
      .flush({ application: 'lead-generation', version: '0.1.0' });
    // The header asks for the models it may offer at the same time. Answered with one,
    // which is the shipped state: a single model is not a choice, so the select stays
    // hidden and only the run button is there.
    httpMock
      .expectOne('/api/scoring-models')
      .flush({ available: ['claude-haiku-4-5'], preferred: 'claude-haiku-4-5' });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('lead-generation 0.1.0');
    httpMock.verify();
  });
});
