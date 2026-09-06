import {Routes} from '@angular/router';

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
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    title: 'Dashboard · Lead Generation',
    loadComponent: () => import('@features/dashboard/dashboard').then((m) => m.Dashboard),
  },
  {
    path: 'analytics',
    title: 'Analytics · Lead Generation',
    loadComponent: () => import('@features/analytics/analytics').then((m) => m.Analytics),
  },
  {
    path: 'shortlist',
    title: 'Shortlist · Lead Generation',
      // Two columns rather than prose, so the same wide measure the board takes; `fill` is
      // what bounds the screen to the viewport, so the two columns scroll and the page does
      // not. Both are stated once: `paramsInheritanceStrategy` defaults to `'always'`, so the
      // child the shell reads as the leaf inherits them.
      data: {wide: true, fill: true},
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
    // Columns, not prose: the board is the one screen the reading measure costs
      // something, so it takes the wide one the shell reads from here. `fill` bounds the
      // screen to the viewport so the board and the detail column scroll on their own.
      data: {wide: true, fill: true},
    loadComponent: () => import('@features/pipeline/pipeline').then((m) => m.Pipeline),
      children: [
          {
              // The offer behind the card, in the same detail component the shortlist opens. A
              // child route rather than a jump to `/shortlist/:id`: the board is where somebody
              // works through what is out, and losing the board to read one ad is the round trip
              // this pattern removes.
              path: ':id',
              title: 'Offer · Lead Generation',
              loadComponent: () =>
                  import('@features/offer-detail/offer-detail').then((m) => m.OfferDetail),
          },
      ],
  },
  {
    path: 'review',
    title: 'Review · Lead Generation',
      // The queue beside the document, so correcting one extraction does not cost the place
      // in the queue. `fill` bounds the screen so both columns scroll on their own.
      data: {wide: true, fill: true},
    loadComponent: () => import('@features/review/review').then((m) => m.Review),
  },
  {
    path: 'sources',
    title: 'Sources · Lead Generation',
    loadComponent: () => import('@features/sources/sources').then((m) => m.Sources),
  },
  {
    path: 'rules',
    title: 'Rules · Lead Generation',
    loadComponent: () => import('@features/rules/rules').then((m) => m.Rules),
  },
  { path: '**', redirectTo: 'dashboard' },
];
