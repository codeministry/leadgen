import { Routes } from '@angular/router';

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
    loadComponent: () => import('@features/shortlist/shortlist-page').then((m) => m.ShortlistPage),
  },
  {
    path: 'offers/:id',
    title: 'Offer · Lead Generation',
    loadComponent: () => import('@features/offer-detail/offer-detail').then((m) => m.OfferDetail),
  },
  {
    path: 'pipeline',
    title: 'Pipeline · Lead Generation',
    // Columns, not prose: the board is the one screen the reading measure costs
    // something, so it takes the wide one the shell reads from here.
    data: { wide: true },
    loadComponent: () => import('@features/pipeline/pipeline').then((m) => m.Pipeline),
  },
  {
    path: 'review',
    title: 'Review · Lead Generation',
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
