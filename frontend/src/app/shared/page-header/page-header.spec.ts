import { TestBed } from '@angular/core/testing';
import { PageHeader } from './page-header';

describe('PageHeader', () => {
  it('renders the title as the page heading and drops an absent subtitle', () => {
    const fixture = TestBed.createComponent(PageHeader);
    fixture.componentRef.setInput('title', 'Shortlist');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Shortlist');
    expect(fixture.nativeElement.querySelector('p')).toBeNull();
  });
});
