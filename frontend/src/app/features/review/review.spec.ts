import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {provideRouter, Router, withComponentInputBinding} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {ManualOfferFields, PendingDocument} from '@core/model/manual-document';
import {Review} from './review';

const DOCUMENT_NAME = 'senior-java.md';

const PENDING: PendingDocument = {
  name: DOCUMENT_NAME,
  size: 412,
  uploadedAt: '2026-09-01T08:00:00Z',
  text: '---\ntitle: Senior Java Entwickler\n---\n\nAblösung eines Monolithen.',
  offer: {
    externalId: null,
    title: 'Senior Java Entwickler',
    description: 'Ablösung eines Monolithen.',
    url: null,
    location: 'Köln',
    portal: null,
    agency: null,
    publishedOn: null,
    tags: [],
    fingerprint: null,
  },
  duplicateOfId: null,
  duplicateOfTitle: null,
};

const FIELDS: ManualOfferFields = {
  title: 'Senior Java Entwickler',
  url: null,
  description: 'Ablösung eines Monolithen.',
  location: 'Köln',
  portal: null,
  agency: null,
  published: null,
  tags: [],
};

describe('Review', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [{path: 'review', loadComponent: () => import('./review').then((m) => m.Review)}],
          withComponentInputBinding(),
        ),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  async function openWithSelection(): Promise<RouterTestingHarness> {
    const harness = await RouterTestingHarness.create(`/review?doc=${DOCUMENT_NAME}`);
    http.expectOne('/api/sources/manual/pending').flush([PENDING]);
    harness.detectChanges();
    return harness;
  }

  it('takes the document out of the URL when it is confirmed', async () => {
    // Confirming moves the file into the inbox, so the name in the query string points at
    // something that no longer exists. Left there, a reload opens a document the queue
    // cannot show and the screen reads as broken.
    const harness = await openWithSelection();
    const review = harness.routeDebugElement!.componentInstance as Review;
    expect(TestBed.inject(Router).url).toContain(`doc=${DOCUMENT_NAME}`);

    review['confirm'](DOCUMENT_NAME, FIELDS);
    // The close is a navigation, so it settles a microtask later than the call.
    await harness.fixture.whenStable();
    harness.detectChanges();

    const request = http.expectOne(
      `/api/sources/manual/pending/${encodeURIComponent(DOCUMENT_NAME)}/confirm`,
    );
    expect(request.request.method).toBe('POST');
    request.flush(PENDING);
    harness.detectChanges();

    expect(TestBed.inject(Router).url).toBe('/review');
  });

  it('takes the document out of the URL when it is rejected', async () => {
    // A rejected upload is a deleted file, so the same rule applies for the same reason.
    const harness = await openWithSelection();
    const review = harness.routeDebugElement!.componentInstance as Review;

    review['reject'](DOCUMENT_NAME);
    await harness.fixture.whenStable();
    harness.detectChanges();

    const request = http.expectOne(
      `/api/sources/manual/pending/${encodeURIComponent(DOCUMENT_NAME)}`,
    );
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
    harness.detectChanges();

    expect(TestBed.inject(Router).url).toBe('/review');
  });

  afterEach(() => http.verify());
});
