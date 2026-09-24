import {Component} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {Title} from '@angular/platform-browser';
import {provideRouter, Route, Routes, TitleStrategy} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {TranslocoService} from '@jsverse/transloco';
import de from '../../../../public/i18n/de.json';
import {routes} from '../../app.routes';
import {BRAND, CatalogTitleStrategy} from './title.strategy';

@Component({template: ''})
class Blank {}

const TEST_ROUTES: Routes = [
    {path: 'dashboard', title: 'title.dashboard', component: Blank},
    {path: 'untitled', component: Blank},
];

/** Every route with a title, however deep, as `path → key`. */
function titled(table: Route[], parent = ''): [string, string][] {
    return table.flatMap((route) => {
        const path = `${parent}/${route.path ?? ''}`;
        const own: [string, string][] = typeof route.title === 'string' ? [[path, route.title]] : [];
        return [...own, ...titled(route.children ?? [], path)];
    });
}

describe('CatalogTitleStrategy', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [provideRouter(TEST_ROUTES), {provide: TitleStrategy, useClass: CatalogTitleStrategy}],
        });
    });

    it('sets the translated screen name followed by the brand', async () => {
        await RouterTestingHarness.create('/dashboard');

        expect(TestBed.inject(Title).getTitle()).toBe('Dashboard · Lead Generation');
    });

    it('sets the brand alone on a route without a title', async () => {
        await RouterTestingHarness.create('/untitled');

        expect(TestBed.inject(Title).getTitle()).toBe(BRAND);
    });

    it('follows a language switch without a navigation', async () => {
        await RouterTestingHarness.create('/dashboard');
        const transloco = TestBed.inject(TranslocoService);
        transloco.setTranslation(de, 'de');
        transloco.setActiveLang('de');

        expect(TestBed.inject(Title).getTitle()).toBe(`${de.title.dashboard} · Lead Generation`);
    });

    it('renders every route title of the application as it read before the titles became keys', () => {
        const transloco = TestBed.inject(TranslocoService);
        const rendered = Object.fromEntries(
            titled(routes).map(([path, key]) => [path, `${transloco.translate(key)} · ${BRAND}`]),
        );

        expect(rendered).toEqual({
            '/dashboard': 'Dashboard · Lead Generation',
            '/analytics': 'Analytics · Lead Generation',
            '/shortlist': 'Shortlist · Lead Generation',
            '/shortlist/:id': 'Offer · Lead Generation',
            '/pipeline': 'Pipeline · Lead Generation',
            '/pipeline/:id': 'Offer · Lead Generation',
            '/sources': 'Sources · Lead Generation',
            '/sources/:id': 'Source · Lead Generation',
            '/rules': 'Rules · Lead Generation',
        });
    });
});
