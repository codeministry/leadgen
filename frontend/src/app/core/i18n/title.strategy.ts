import {inject, Injectable} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {Title} from '@angular/platform-browser';
import {RouterStateSnapshot, TitleStrategy} from '@angular/router';
import {TranslocoService} from '@jsverse/transloco';
import {map, of, Subject, switchMap} from 'rxjs';

/**
 * The application's name in the browser tab, after every screen name and alone where a
 * route has none. It is the text `src/index.html` shows before the first navigation, and
 * not the `LEADgen` wordmark the header draws: the two are separate things.
 */
export const BRAND = 'Lead Generation';

/**
 * Route titles are catalog keys (`title.dashboard`), and the tab reads
 * `<screen name> · Lead Generation` in the active language.
 *
 * <p>`selectTranslate` rather than `translate`: the catalogs are fetched over HTTP, so the
 * first navigation can arrive before one has loaded, and a synchronous lookup would put the
 * bare key in the tab. It also emits again when the language changes, which keeps the tab
 * in step with a switch that navigates nowhere.
 */
@Injectable({providedIn: 'root'})
export class CatalogTitleStrategy extends TitleStrategy {
    private readonly title = inject(Title);
    private readonly transloco = inject(TranslocoService);
    private readonly keys = new Subject<string | undefined>();

    constructor() {
        super();
        this.keys
            .pipe(
                switchMap((key) =>
                    key === undefined
                        ? of(BRAND)
                        : this.transloco.selectTranslate<string>(key).pipe(map((screen) => `${screen} · ${BRAND}`)),
                ),
                takeUntilDestroyed(),
            )
            .subscribe((text) => this.title.setTitle(text));
    }

    override updateTitle(snapshot: RouterStateSnapshot): void {
        this.keys.next(this.buildTitle(snapshot));
    }
}
