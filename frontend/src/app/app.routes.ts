import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'shortlist' },
  {
    path: 'shortlist',
    loadComponent: () =>
      import('@features/shortlist/shortlist-page').then((m) => m.ShortlistPage),
  },
];
