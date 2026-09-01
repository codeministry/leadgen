import { TestBed } from '@angular/core/testing';
import { EmptyState } from './empty-state';

describe('EmptyState', () => {
  it('shows the direction, not only the absence', () => {
    const fixture = TestBed.createComponent(EmptyState);
    fixture.componentRef.setInput('title', 'Nothing survived the filter today');
    fixture.componentRef.setInput('description', 'Widen the radius in Rules, or run ingest again.');
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Nothing survived the filter today');
    expect(text).toContain('Widen the radius');
  });
});
