import {TestBed} from '@angular/core/testing';
import {Dispatcher} from '@ngrx/signals/events';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {densityEvents} from './density.events';
import {DensityStore} from './density.store';
import {DENSITY_STORAGE_KEY, isListDensity} from './density.model';

describe('DensityStore', () => {
  beforeEach(() => {
    localStorage.removeItem(DENSITY_STORAGE_KEY);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('starts comfortable with nothing stored', () => {
    const store = TestBed.inject(DensityStore);
    TestBed.tick();

    expect(store.density()).toBe('comfortable');
  });

  it('restores what this browser stored', () => {
    localStorage.setItem(DENSITY_STORAGE_KEY, 'compact');

    const store = TestBed.inject(DensityStore);
    TestBed.tick();

    expect(store.density()).toBe('compact');
  });

  it('persists a choice, and a store created from the same storage reads it back', () => {
    const store = TestBed.inject(DensityStore);
    TestBed.inject(Dispatcher).dispatch(densityEvents.chosen('compact'));
    TestBed.tick();

    expect(store.density()).toBe('compact');
    expect(localStorage.getItem(DENSITY_STORAGE_KEY)).toBe('compact');

    // A reload, as far as a unit test can have one: a fresh injector over the same storage.
    TestBed.resetTestingModule();
    const reloaded = TestBed.inject(DensityStore);
    TestBed.tick();
    expect(reloaded.density()).toBe('compact');
  });

  it('falls back to comfortable when storage holds something it never wrote', () => {
    localStorage.setItem(DENSITY_STORAGE_KEY, 'cozy');

    const store = TestBed.inject(DensityStore);
    TestBed.tick();

    expect(store.density()).toBe('comfortable');
  });

  it('renders comfortable and keeps working when storage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });

    const store = TestBed.inject(DensityStore);
    TestBed.tick();
    expect(store.density()).toBe('comfortable');

    TestBed.inject(Dispatcher).dispatch(densityEvents.chosen('compact'));
    TestBed.tick();
    expect(store.density()).toBe('compact');
  });

  it('accepts the two densities and nothing else', () => {
    expect(isListDensity('comfortable')).toBe(true);
    expect(isListDensity('compact')).toBe(true);
    expect(isListDensity('dense')).toBe(false);
    expect(isListDensity(null)).toBe(false);
  });
});
