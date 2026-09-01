import { TestBed } from '@angular/core/testing';
import { ManualOfferFields, PendingDocument } from '@core/model/manual-document';
import { ReviewCard } from './review-card';

function document(overrides: Partial<PendingDocument> = {}): PendingDocument {
  return {
    name: 'offer.md',
    size: 128,
    uploadedAt: '2026-09-01T10:00:00Z',
    text: '---\ntitle: Senior Java Entwickler\n---\nAblösung eines Monolithen.',
    offer: {
      externalId: 'https://portal.example/p/1',
      title: 'Senior Java Entwickler',
      description: 'Ablösung eines Monolithen.',
      url: 'https://portal.example/p/1',
      location: 'Köln',
      portal: null,
      agency: null,
      publishedOn: null,
      tags: ['Java', 'Spring Boot'],
      fingerprint: 'senior java entwickler',
    },
    duplicateOfId: null,
    duplicateOfTitle: null,
    ...overrides,
  };
}

describe('ReviewCard', () => {
  function render(pending: PendingDocument) {
    const fixture = TestBed.createComponent(ReviewCard);
    fixture.componentRef.setInput('document', pending);
    fixture.detectChanges();
    return fixture;
  }

  it('emits the corrected fields, with an empty one as not stated', () => {
    const fixture = render(document());
    const emitted: ManualOfferFields[] = [];
    fixture.componentInstance.confirmed.subscribe((fields) => emitted.push(fields));

    const title: HTMLInputElement = fixture.nativeElement.querySelector('#title-offer\\.md');
    title.value = '  Senior Java Entwickler, korrigiert  ';
    title.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.btn-primary').click();

    expect(emitted).toHaveLength(1);
    expect(emitted[0]?.title).toBe('Senior Java Entwickler, korrigiert');
    // Never an empty string in the frontmatter: the pipeline treats null and "" alike,
    // but a written-out empty key reads as a value somebody entered.
    expect(emitted[0]?.portal).toBeNull();
    expect(emitted[0]?.tags).toEqual(['Java', 'Spring Boot']);
  });

  it('cannot be confirmed without a title, because an offer without one is dropped', () => {
    const fixture = render(document({ offer: null }));

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.btn-primary');
    expect(button.disabled).toBe(true);
  });

  it('says when the pipeline already holds the same title', () => {
    const fixture = render(
      document({ duplicateOfId: 7, duplicateOfTitle: 'Senior Java Entwickler' }),
    );

    expect(fixture.nativeElement.textContent).toContain('Already in the pipeline');
  });

  it('shows the file beside the fields, because a wrong reading is only visible against it', () => {
    const fixture = render(document());

    expect(fixture.nativeElement.querySelector('.text').textContent).toContain(
      'Ablösung eines Monolithen.',
    );
  });
});
