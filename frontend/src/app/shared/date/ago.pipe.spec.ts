import {TestBed} from '@angular/core/testing';
import {TranslocoService} from '@jsverse/transloco';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {AgoPipe} from './ago.pipe';

describe('AgoPipe', () => {
  // A fixed "now" in the middle of a local day, so a day boundary is never a coin toss.
  const NOW = new Date(2026, 8, 25, 12, 0, 0);

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function pipe(): AgoPipe {
    return TestBed.runInInjectionContext(() => new AgoPipe());
  }

  function daysBefore(days: number, hour = 9): string {
    return new Date(2026, 8, 25 - days, hour, 30).toISOString();
  }

  it('says how many days ago in English', () => {
    TestBed.inject(TranslocoService).setActiveLang('en');
    expect(pipe().transform(daysBefore(3))).toBe('3 days ago');
  });

  it('says how many days ago in German', () => {
    TestBed.inject(TranslocoService).setActiveLang('de');
    expect(pipe().transform(daysBefore(3))).toBe('vor 3 Tagen');
  });

  it('names yesterday rather than counting to one', () => {
    TestBed.inject(TranslocoService).setActiveLang('en');
    expect(pipe().transform(daysBefore(1))).toBe('yesterday');
    TestBed.inject(TranslocoService).setActiveLang('de');
    expect(pipe().transform(daysBefore(1))).toBe('gestern');
  });

  it('says today for anything earlier on the same day', () => {
    TestBed.inject(TranslocoService).setActiveLang('en');
    expect(pipe().transform(daysBefore(0, 1))).toBe('today');
  });

  it('keeps counting in days past a month, days being the coarsest unit', () => {
    TestBed.inject(TranslocoService).setActiveLang('en');
    expect(pipe().transform(daysBefore(45))).toBe('45 days ago');
  });

  it('answers null for nothing and hands back what it cannot read', () => {
    expect(pipe().transform(null)).toBeNull();
    expect(pipe().transform('')).toBeNull();
    expect(pipe().transform('sofort')).toBe('sofort');
  });
});
