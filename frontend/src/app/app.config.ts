import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withFetch()),
    // Router-driven state over in-memory state: a filtered shortlist has to
    // survive a reload and be shareable as a link, so the query params are the
    // source of truth and bind straight into component inputs.
    provideRouter(routes, withComponentInputBinding()),
  ],
};
