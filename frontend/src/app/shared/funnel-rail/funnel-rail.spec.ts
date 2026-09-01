import { TestBed } from '@angular/core/testing';
import { FunnelRail } from './funnel-rail';
import { FunnelStage } from './funnel-stage';

const STAGES: readonly FunnelStage[] = [
  { id: 'abroad', label: 'Abroad', removed: 25 },
  { id: 'remote-share', label: 'Remote share below 80 %', removed: 12 },
  { id: 'distance', label: 'Beyond reach, not remote', removed: 717 },
  { id: 'stack-role', label: 'Foreign stack or wrong role', removed: 115 },
  { id: 'core-skill', label: 'No core skill', removed: 171 },
  { id: 'contract', label: 'Contract form rejected', removed: 8 },
  { id: 'stale', label: 'Older than 21 days', removed: 2 },
];

describe('FunnelRail', () => {
  it('reproduces the measured baseline: 1289 in, 239 out', () => {
    const fixture = TestBed.createComponent(FunnelRail);
    fixture.componentRef.setInput('stages', STAGES);
    fixture.componentRef.setInput('total', 1289);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('1,289');
    expect(text).toContain('239');
    expect(text).toContain('18.5 %');
  });

  it('starts every bar at full width so the reveal has something to transition from', () => {
    const fixture = TestBed.createComponent(FunnelRail);
    fixture.componentRef.setInput('stages', STAGES);
    fixture.componentRef.setInput('total', 1289);
    fixture.detectChanges();

    // Before the post-render frame lands, the rail is a block of full-width bars.
    // A width rendered correctly the first time would never fire a transition.
    const widths: string[] = Array.from(
      fixture.nativeElement.querySelectorAll('.row:not(.head) .fill'),
      (el) => (el as HTMLElement).style.width,
    );
    expect(widths.length).toBe(STAGES.length + 1);
    expect(widths.every((width) => width === '100%')).toBe(true);
  });

  it('does not divide by zero on an empty run', () => {
    const fixture = TestBed.createComponent(FunnelRail);
    fixture.componentRef.setInput('stages', []);
    fixture.componentRef.setInput('total', 0);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('0.0 %');
  });
});
