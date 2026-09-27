import {TestBed} from '@angular/core/testing';
import {TranslocoTestingModule} from '@jsverse/transloco';
import {provideTranslocoMessageformat} from '@jsverse/transloco-messageformat';
import en from '../public/i18n/en.json';

/**
 * Every component that renders a sentence goes through the `transloco` pipe, and the pipe
 * needs the service. Provided globally rather than in each spec: a spec that forgets it
 * fails with `No provider found for TRANSLOCO_TRANSPILER` from inside a component that has
 * nothing to do with i18n, which is a long way from the cause.
 *
 * <p>The real English catalog, not a stub. A test asserting on "Run ingest" is asserting on
 * what the screen says, and a stub would let the catalog and the templates drift apart
 * without a single test noticing.
 */
/**
 * jsdom has no 2D context, and zrender asks for one to measure text even on the SVG
 * renderer. The measurement is never asserted on — every chart component renders its
 * numbers as a table and the specs read that — so the stub only keeps six "Not
 * implemented" lines per run out of an otherwise clean suite. Installing the native
 * `canvas` package to silence a warning would put a node-gyp build in CI.
 */
if (typeof HTMLCanvasElement !== 'undefined') {
    HTMLCanvasElement.prototype.getContext = (() =>
        null) as unknown as typeof HTMLCanvasElement.prototype.getContext;
}

/**
 * jsdom has no `ResizeObserver`, and the graph library behind the rules flow canvas constructs one in a
 * service and for `view="auto"`. Every spec that renders the rules screen would otherwise
 * fail with "ResizeObserver is not defined". Nothing is ever observed in jsdom anyway: it
 * lays nothing out, and the browser tier measures real boxes.
 */
if (typeof globalThis.ResizeObserver === 'undefined') {
    globalThis.ResizeObserver = class {
        observe(): void {
            /* jsdom lays nothing out, so there is nothing to observe */
        }
        unobserve(): void {
            /* see observe */
        }
        disconnect(): void {
            /* see observe */
        }
    };
}

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
