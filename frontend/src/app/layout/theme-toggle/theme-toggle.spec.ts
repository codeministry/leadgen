import { TestBed } from '@angular/core/testing';
import { ThemeStore } from '@core/theme/theme.store';
import { DATA_THEME_ATTR, THEME_STORAGE_KEY } from '@core/theme/theme.model';
import { ThemeToggle } from './theme-toggle';

describe('ThemeToggle', () => {
  beforeEach(() => {
    localStorage.removeItem(THEME_STORAGE_KEY);
    document.documentElement.removeAttribute(DATA_THEME_ATTR);
  });

  it('offers the three states and checks exactly the active one', () => {
    const fixture = TestBed.createComponent(ThemeToggle);
    fixture.detectChanges();

    const buttons: HTMLButtonElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('button'),
    );
    expect(buttons.length).toBe(3);
    expect(buttons.filter((b) => b.getAttribute('aria-checked') === 'true').length).toBe(1);
  });

  it('dispatches the choice through the store', () => {
    const fixture = TestBed.createComponent(ThemeToggle);
    const store = TestBed.inject(ThemeStore);
    fixture.detectChanges();

    const dark: HTMLButtonElement = fixture.nativeElement.querySelectorAll('button')[2];
    dark.click();
    fixture.detectChanges();

    expect(store.preference()).toBe('dark');
    expect(store.theme()).toBe('lg-dark');
  });
});
