import {TestBed} from '@angular/core/testing';
import {injectDispatch} from '@ngrx/signals/events';
import {filterViewEvents} from './filter-views.events';
import {FILTER_VIEWS_STORAGE_KEY} from './filter-view.model';
import {FilterViewsStore} from './filter-views.store';

/**
 * What a browser's own list of saved views has to survive: a key somebody edited, a key half
 * written by a tab that was closed mid-save, and a private window where storage throws.
 */
describe('FilterViewsStore', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  function store() {
    return TestBed.inject(FilterViewsStore);
  }

  it('reads what was stored and keeps only the entries that are one', () => {
    // One bad entry costs one view and not the whole list, which is the difference between a
    // half-written key and an empty sidebar.
    localStorage.setItem(
      FILTER_VIEWS_STORAGE_KEY,
      JSON.stringify([
        {id: 'a', name: 'Remote', query: 'band=shortlist'},
        {id: 'b', name: 'Kaputt'},
        'not an object',
      ]),
    );

    expect(store().views()).toEqual([{id: 'a', name: 'Remote', query: 'band=shortlist'}]);
  });

  it('starts empty on a key that is not JSON at all', () => {
    localStorage.setItem(FILTER_VIEWS_STORAGE_KEY, '{oh no');

    expect(store().views()).toEqual([]);
  });

  it('appends a saved view, trimmed, and writes the list back', () => {
    const views = store();
    TestBed.runInInjectionContext(() => {
      injectDispatch(filterViewEvents).saved({name: '  Remote  ', query: 'minMonths=6'});
    });
    TestBed.tick();

    expect(views.views()).toHaveLength(1);
    expect(views.views()[0]!.name).toBe('Remote');
    expect(views.views()[0]!.query).toBe('minMonths=6');

    // The effect is the whole persistence layer, so the assertion is on storage and not on a
    // method: a view that is in the signal and not in the key is a view that is gone tomorrow.
    const stored: unknown = JSON.parse(localStorage.getItem(FILTER_VIEWS_STORAGE_KEY) ?? '[]');
    expect(stored).toHaveLength(1);
  });

  it('removes the view that was named and leaves the rest', () => {
    localStorage.setItem(
      FILTER_VIEWS_STORAGE_KEY,
      JSON.stringify([
        {id: 'a', name: 'Remote', query: 'band=shortlist'},
        {id: 'b', name: 'Lang', query: 'minMonths=12'},
      ]),
    );
    const views = store();

    TestBed.runInInjectionContext(() => injectDispatch(filterViewEvents).removed('a'));
    TestBed.tick();

    expect(views.views().map((view) => view.id)).toEqual(['b']);
  });
});
