import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideI18n } from '@core/i18n/transloco.providers';
import { provideChartPalette } from '@core/theme/chart-theme';
import { provideScoreThresholds } from '@core/store/score-thresholds.provider';
import { routes } from './app.routes';

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
