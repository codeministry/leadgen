import { Routes } from '@angular/router';

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
