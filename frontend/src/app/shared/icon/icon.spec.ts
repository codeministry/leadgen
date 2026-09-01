import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Icon } from './icon';

@Component({
  imports: [Icon],
  template: `<lg-icon name="funnel" [label]="label()" />`,
})
class Host {
  readonly label = signal<string | null>(null);
}

describe('Icon', () => {
  it('renders the icon geometry', () => {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();

    const paths = fixture.nativeElement.querySelectorAll('svg path');
    expect(paths.length).toBeGreaterThan(0);
    expect(paths[0].getAttribute('d')).toBeTruthy();
  });

  it('is decorative without a label and an image with one', () => {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();

    const svg = fixture.nativeElement.querySelector('svg');
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(svg.getAttribute('role')).toBeNull();

    fixture.componentInstance.label.set('Filter');
    fixture.detectChanges();

    expect(svg.getAttribute('aria-hidden')).toBeNull();
    expect(svg.getAttribute('role')).toBe('img');
    expect(svg.getAttribute('aria-label')).toBe('Filter');
  });
});
