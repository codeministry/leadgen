import {
    ApplicationConfig,
    inject,
    isDevMode,
    provideAppInitializer,
    provideBrowserGlobalErrorListeners,
} from '@angular/core';
import {provideHttpClient, withFetch, withInterceptors} from '@angular/common/http';
import {provideRouter, TitleStrategy, withComponentInputBinding} from '@angular/router';
import {provideServiceWorker} from '@angular/service-worker';
import {provideOAuthClient} from 'angular-oauth2-oidc';
import {AuthService} from '@core/auth/auth.service';
import {bearerInterceptor} from '@core/auth/bearer.interceptor';
import {CatalogTitleStrategy} from '@core/i18n/title.strategy';
import {provideI18n} from '@core/i18n/transloco.providers';
import {UpdateStore} from '@core/pwa/update.store';
import {provideChartPalette} from '@core/theme/chart-theme';
import {provideScoreThresholds} from '@core/store/score-thresholds.provider';
import {routes} from './app.routes';

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
        provideHttpClient(withFetch(), withInterceptors([bearerInterceptor])),
        provideOAuthClient(),
        // Before the first route, because a screen that renders and then redirects has
        // already made requests that will come back 401. Under `auth: none` this resolves
        // after one request and does nothing else.
        provideAppInitializer(() => inject(AuthService).initialise()),
        provideI18n(),
        // The seam `shared/` reaches the theme through: a chart takes colour strings, and
        // only the layers above shared may know where they come from.
        provideChartPalette(),
        provideScoreThresholds(),
        // Router-driven state over in-memory state: a filtered shortlist has to
        // survive a reload and be shareable as a link, so the query params are the
        // source of truth and bind straight into component inputs.
        provideRouter(routes, withComponentInputBinding()),
        // Route titles are catalog keys; this puts the screen name and the brand in the tab.
        {provide: TitleStrategy, useClass: CatalogTitleStrategy},
        // The shell offline, the data never: the worker caches what `ngsw-config.json` names
        // and nothing under `/api/`. Keyed on the build mode rather than the hostname, because
        // the dev server on :4200 must never hold a stale bundle, while the compose stack on
        // localhost is the production artifact the install is verified against. Registered
        // once the app is stable, or after 30 s, so it never competes with the first paint.
        provideServiceWorker('ngsw-worker.js', {
            enabled: !isDevMode(),
            registrationStrategy: 'registerWhenStable:30000',
        }),
        // Injected for its existence, like `RefreshStore` in `app.ts`: nothing reads the
        // update store, so unreferenced it would never be constructed and a deploy would go
        // unannounced. Here rather than in `app.ts` because it needs `SwUpdate`, which only
        // the provider above supplies — a component spec without it would fail on the store.
        // Under a disabled worker the store subscribes to nothing.
        provideAppInitializer(() => {
            inject(UpdateStore);
        }),
    ],
};
