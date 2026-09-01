import { TestBed } from '@angular/core/testing';
import { Dispatcher } from '@ngrx/signals/events';
import { themeEvents } from './theme.events';
import { ThemeStore } from './theme.store';
import { DATA_THEME_ATTR, THEME_STORAGE_KEY } from './theme.model';

describe('ThemeStore', () => {
  beforeEach(() => {
    localStorage.removeItem(THEME_STORAGE_KEY);
    document.documentElement.removeAttribute(DATA_THEME_ATTR);
  });

  it('starts on the system preference and writes no attribute for it', () => {
    const store = TestBed.inject(ThemeStore);
    TestBed.tick();

    expect(store.preference()).toBe('system');
    expect(document.documentElement.hasAttribute(DATA_THEME_ATTR)).toBe(false);
  });

  it('writes the attribute for an explicit choice and takes it back off for system', () => {
    const store = TestBed.inject(ThemeStore);
    const dispatcher = TestBed.inject(Dispatcher);

    dispatcher.dispatch(themeEvents.chosen('dark'));
    TestBed.tick();
    expect(store.theme()).toBe('lg-dark');
    expect(document.documentElement.getAttribute(DATA_THEME_ATTR)).toBe('lg-dark');

    dispatcher.dispatch(themeEvents.chosen('system'));
    TestBed.tick();
    expect(document.documentElement.hasAttribute(DATA_THEME_ATTR)).toBe(false);
  });

  it('resolves system against the OS rather than defaulting to light', () => {
    const store = TestBed.inject(ThemeStore);
    const dispatcher = TestBed.inject(Dispatcher);

    dispatcher.dispatch(themeEvents.systemChanged(true));
    TestBed.tick();

    expect(store.preference()).toBe('system');
    expect(store.theme()).toBe('lg-dark');
  });

  it('persists the preference, not the resolved theme', () => {
    const dispatcher = TestBed.inject(Dispatcher);
    TestBed.inject(ThemeStore);

    dispatcher.dispatch(themeEvents.chosen('light'));
    TestBed.tick();

    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light');
  });
});
