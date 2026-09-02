import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideI18n } from '@core/i18n/transloco.providers';
import { provideChartPalette } from '@core/theme/chart-theme';
import { provideScoreThresholds } from '@core/store/score-thresholds.provider';
import { routes } from './app.routes';

/**
 * What the application is wired with, and the two seams worth knowing about.
 *
 * `withComponentInputBinding` is what makes the query string the source of truth for the
 * shortlist's filters. Note the consequence documented on those inputs: an absent parameter
 * binds as `undefined` and overrides a declared default, so every routed input needs a
 * `transform` that puts the default back.
 *
 * The two `provide*` calls below are the only way `shared/` reaches anything it is not
 * allowed to import. It takes colour strings and threshold numbers through tokens; where
 * they come from is knowledge the layers above it hold.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withFetch()),
    provideI18n(),
    // The seam `shared/` reaches the theme through: a chart takes colour strings, and
    // only the layers above shared may know where they come from.
    provideChartPalette(),
    provideScoreThresholds(),
    // Router-driven state over in-memory state: a filtered shortlist has to
    // survive a reload and be shareable as a link, so the query params are the
    // source of truth and bind straight into component inputs.
    provideRouter(routes, withComponentInputBinding()),
  ],
};
