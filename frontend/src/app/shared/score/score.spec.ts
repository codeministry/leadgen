import { TestBed } from '@angular/core/testing';
import { Score } from './score';

describe('Score', () => {
  function bandFor(value: number | null): string | null {
    const fixture = TestBed.createComponent(Score);
    fixture.componentRef.setInput('value', value);
    fixture.detectChanges();
    return fixture.nativeElement.querySelector('.score').getAttribute('data-band');
  }

  it('bands on the thresholds from matching-rules.yaml', () => {
    expect(bandFor(70)).toBe('strong');
    expect(bandFor(69)).toBe('weak');
    expect(bandFor(50)).toBe('weak');
    expect(bandFor(49)).toBe('out');
  });

  it('shows an unscored offer as unscored, not as zero', () => {
    const fixture = TestBed.createComponent(Score);
    fixture.componentRef.setInput('value', null);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.score').getAttribute('data-band')).toBe(
      'unscored',
    );
    expect(fixture.nativeElement.querySelector('.figure').textContent).toContain('—');
    expect(fixture.nativeElement.querySelector('.fill')).toBeNull();
    expect(fixture.nativeElement.querySelector('svg').getAttribute('aria-label')).toBe(
      'Not scored',
    );
  });
});
