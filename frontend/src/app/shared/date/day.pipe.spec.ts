import {TestBed} from '@angular/core/testing';
import {TranslocoService} from '@jsverse/transloco';
import {describe, expect, it} from 'vitest';
import {DayPipe} from './day.pipe';

describe('DayPipe', () => {
  function pipe(): DayPipe {
    return TestBed.runInInjectionContext(() => new DayPipe());
  }

  it('writes the day the language writes it', () => {
    TestBed.inject(TranslocoService).setActiveLang('de');
    expect(pipe().transform('2026-09-02')).toContain('2026');
    expect(pipe().transform('2026-09-02')).not.toBe('2026-09-02');
  });

  it('shows the day that was stored, not the one the timezone lands on', () => {
    // A bare YYYY-MM-DD parsed as UTC midnight and formatted locally shows the previous
    // day west of Greenwich. The parts are read off the string for exactly this reason.
    TestBed.inject(TranslocoService).setActiveLang('en');
    expect(pipe().transform('2026-01-01')).toContain('1');
    expect(pipe().transform('2026-01-01')).toContain('2026');
  });

  it('answers null for nothing, so the row reads as not known', () => {
    expect(pipe().transform(null)).toBeNull();
    expect(pipe().transform('')).toBeNull();
  });

  it('hands back anything it cannot read rather than an empty string', () => {
    // An empty string reads as "not known" to the template beside it, which is a
    // different claim from "this is what the server sent".
    expect(pipe().transform('sofort')).toBe('sofort');
  });
});
