import {Routes} from '@angular/router';
import {Section} from '@core/theme/section.model';

/**
 * Every screen, lazily loaded.
 *
 * `title` is set per route rather than in a component: the browser tab is the only place
 * two open screens of this application are told apart, and a route that forgets it inherits
 * whichever title happened to be set last.
 *
 * The unknown path redirects rather than showing a not-found page. There is nothing here a
 * stranger could deep-link into wrongly, and the dashboard is the honest answer to "that
 * URL does not exist".
 *
 * `data.section` names the navigation destination a route belongs to, typed as `Section`
 * so a misspelling fails the build; the shell writes it as `data-section` on its host and
 * the section colour hangs off that. Children inherit it, which is how the offer detail
 * takes the shortlist's colour under one parent and the pipeline's under the other.
 */
const section = (s: Section): {section: Section} => ({section: s});

export const routes: Routes = [
    {path: '', pathMatch: 'full', redirectTo: 'dashboard'},
    {
        path: 'dashboard',
        title: 'Dashboard · Lead Generation',
        data: section('dashboard'),
        loadComponent: () => import('@features/dashboard/dashboard').then((m) => m.Dashboard),
    },
    {
        path: 'analytics',
        title: 'Analytics · Lead Generation',
        data: section('analytics'),
        loadComponent: () => import('@features/analytics/analytics').then((m) => m.Analytics),
    },
    {
        path: 'shortlist',
        title: 'Shortlist · Lead Generation',
        // `section` is inherited by the `:id` child, so the detail keeps the shortlist's colour.
        data: section('shortlist'),
      // No `measure` and no `fill`: the shell's own bound is what this screen wants, and the
      // advert is what wants a page — the detail column scrolls with the document and the
      // list column pins itself beside it. The board keeps `fill`, because five lanes
      // dividing a growing page is two gestures with two owners.
        loadComponent: () => import('@features/shortlist/shortlist-page').then((m) => m.ShortlistPage),
        children: [
            {
                // A child route rather than a second flat route on the same component: two `Route`
                // objects are two configurations, so the default reuse strategy would destroy the
                // list on the first click — refetching it and losing the scroll position every time
                // an offer is opened. And a single route cannot express an optional path parameter.
                path: ':id',
                // Its own title: two tabs, one on the list and one on an offer, are told apart
                // nowhere else.
                title: 'Offer · Lead Generation',
                loadComponent: () =>
                    import('@features/offer-detail/offer-detail').then((m) => m.OfferDetail),
            },
        ],
    },
    // Every link and bookmark written before the detail moved into the shortlist. The id is
    // carried across: a `:name` in `redirectTo` is substituted from the matched segments.
    {path: 'offers/:id', redirectTo: '/shortlist/:id'},
    {
        path: 'pipeline',
        title: 'Pipeline · Lead Generation',
        // The whole window, not a measure: five lanes and a reading column divide whatever
        // width there is, so a cap here is width taken off every lane. The other two split
      // views take the shell's bound — they have one prose column, which does get
      // unreadable. `fill` bounds the screen to the viewport so the board and the detail
      // column scroll on their own.
        data: {measure: 'full', fill: true, ...section('pipeline')},
        loadComponent: () => import('@features/pipeline/pipeline').then((m) => m.Pipeline),
        children: [
            {
                // The offer behind the card, in the same detail component the shortlist opens. A
                // child route rather than a jump to `/shortlist/:id`: the board is where somebody
                // works through what is out, and losing the board to read one ad is the round trip
                // this pattern removes.
                path: ':id',
                title: 'Offer · Lead Generation',
              // Where the detail's close control goes, bound straight onto the component's
              // `closeTo` input by `withComponentInputBinding()`. Route data rather than a
              // flag the component derives from the URL: the shortlist auto-selects its
              // first entry, so closing there would re-open it on the next tick, and the
              // honest way to say "this one is closable and that one is not" is in the
              // route that knows.
              data: {closeTo: '/pipeline'},
                loadComponent: () =>
                    import('@features/offer-detail/offer-detail').then((m) => m.OfferDetail),
            },
        ],
    },
    // The review screen is parked (2026-09-24): not important right now, to be reworked when there
    // is time. Its code stays under `features/review/` with its specs; putting it back is this
    // route, its nav entry in `layout/app-nav/app-nav.ts` and `'review'` in `core/theme/section.model.ts`.
    {
        path: 'sources',
        title: 'Sources · Lead Generation',
        data: section('sources'),
        loadComponent: () => import('@features/sources/sources').then((m) => m.Sources),
      children: [
        {
          // A child route on a real component, so the table stays mounted and its numbers
          // are not refetched on every click. A componentless leaf is refused outright
          // with NG04014, and that failure surfaces when the Router is constructed — in
          // every spec that merely injects it, far from the route that caused it.
          path: ':id',
          loadComponent: () =>
            import('@features/sources/source-panel/source-panel').then((m) => m.SourcePanel),
        },
      ],
    },
    {
        path: 'rules',
        title: 'Rules · Lead Generation',
        data: section('rules'),
        loadComponent: () => import('@features/rules/rules').then((m) => m.Rules),
    },
    {path: '**', redirectTo: 'dashboard'},
];
