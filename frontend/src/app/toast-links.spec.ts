import {TestBed} from '@angular/core/testing';
import {Route} from '@angular/router';
import {injectDispatch} from '@ngrx/signals/events';
import {ApplicationView} from '@core/model/application';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {applicationEvents} from '@core/store/applications.events';
import {shortlistEvents} from '@core/store/shortlist.events';
import {ToastStore} from '@core/toast/toast.store';
import {routes} from './app.routes';

/** Every configured path, children joined under their parent, `:param` kept as is. */
function patterns(tree: readonly Route[], prefix = ''): string[] {
    return tree.flatMap((route) => {
        const own = [prefix, route.path ?? ''].filter((s) => s !== '').join('/');
        return [own, ...patterns(route.children ?? [], own)];
    });
}

function resolves(link: string, configured: readonly string[]): boolean {
    const segments = link.replace(/^\//, '').split('/');
    return configured.some((pattern) => {
        const parts = pattern.split('/');
        return parts.length === segments.length
            && parts.every((part, i) => part.startsWith(':') ? segments[i] !== '' : part === segments[i]);
    });
}

/**
 * A toast never writes; its one control closes it and its link is a navigation. This
 * holds the second half: every link the store can raise lands on a route that exists,
 * so a toast never points at a page the app does not have. At the app level because it
 * needs the route table and the store together, and neither layer may import the other.
 */
describe('toast links', () => {
    it('all resolve against the configured routes', () => {
        const store = TestBed.inject(ToastStore);
        const shortlist = TestBed.runInInjectionContext(() => injectDispatch(shortlistEvents));
        const applications = TestBed.runInInjectionContext(() => injectDispatch(applicationEvents));

        shortlist.archived({offer: {id: 7, title: 'x', archivedAt: null}} as unknown as ShortlistEntry);
        shortlist.rescored({offer: {id: 9, title: 'x', archivedAt: null}, score: {value: 50}} as unknown as ShortlistEntry);
        applications.updated({id: 3, offerId: 9, status: 'SENT', title: 'x'} as unknown as ApplicationView);

        const links = store.toasts().map((standing) => standing.link).filter((link): link is string => link !== undefined);
        const configured = patterns(routes).filter((pattern) => pattern !== '**');

        expect(links.length).toBe(3);
        for (const link of links) {
            expect(resolves(link, configured), link).toBe(true);
        }
    });
});
