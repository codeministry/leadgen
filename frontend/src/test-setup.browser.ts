import {TestBed} from '@angular/core/testing';
import {TranslocoTestingModule} from '@jsverse/transloco';
import {provideTranslocoMessageformat} from '@jsverse/transloco-messageformat';
import en from '../public/i18n/en.json';

/**
 * The setup for the browser tier (`bun run test:browser`, every `*.browser.spec.ts`).
 *
 * <p>The same Transloco wiring as `test-setup.ts`, for the same reason, and deliberately
 * <b>without</b> the canvas stub that file installs: the browser tier exists so a spec can
 * resolve a colour through a real 2D context, which is the one thing jsdom cannot do. A
 * stub here would make the contrast spec pass with every element invisible.
 */
beforeEach(() => {
    TestBed.configureTestingModule({
        imports: [
            TranslocoTestingModule.forRoot({
                langs: {en},
                translocoConfig: {availableLangs: ['en', 'de'], defaultLang: 'en'},
                preloadLangs: true,
            }),
        ],
        providers: [provideTranslocoMessageformat()],
    });
});
