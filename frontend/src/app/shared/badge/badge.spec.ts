import { TestBed } from '@angular/core/testing';
import { Badge } from './badge';

describe('Badge', () => {
  it('maps every tone onto a class that exists as a literal in the source', () => {
    const fixture = TestBed.createComponent(Badge);
    fixture.componentRef.setInput('tone', 'accent');
    fixture.detectChanges();

    const span = fixture.nativeElement.querySelector('span');
    expect(span.className).toContain('badge-accent');
    expect(span.className).not.toContain('badge-outline');

    fixture.componentRef.setInput('tone', 'success');
    fixture.componentRef.setInput('outline', true);
    fixture.detectChanges();

    expect(span.className).toContain('badge-success');
    expect(span.className).toContain('badge-outline');
  });
});
