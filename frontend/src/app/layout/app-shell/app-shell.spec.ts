import {provideHttpClient} from '@angular/common/http';
import {provideHttpClientTesting} from '@angular/common/http/testing';
import {Component} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {provideRouter, Router, Routes} from '@angular/router';
import {routes} from '../../app.routes';
import {SECTIONS} from '@core/theme/section.model';
import {AppShell} from './app-shell';

@Component({selector: 'lg-stub-screen', template: ''})
class StubScreen {}

/**
 * The real table's shape (which section each destination carries), then the shell's
 * mechanism on a stub table with the same shape: the seven screens are lazy and each
 * would pull its stores into this spec, while the question is about the route data and
 * the attribute, not about the screens.
 */
const STUB_ROUTES: Routes = [
    {path: '', pathMatch: 'full', redirectTo: 'dashboard'},
    ...SECTIONS.map(section => ({
        path: section,
        data: {section},
        component: StubScreen,
        children: [{path: ':id', component: StubScreen}],
    })),
    {path: '**', redirectTo: 'dashboard'},
];

describe('AppShell', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [provideRouter(STUB_ROUTES), provideHttpClient(), provideHttpClientTesting()],
        });
    });

    it('renders the toast stack exactly once, beside the header and the outlet', () => {
        const fixture = TestBed.createComponent(AppShell);
        fixture.detectChanges();

        // One mechanism: a second stack would be a second z-index and a second announcement
        // for every toast. A screen that wants one renders nothing; the shell already has it.
        expect(fixture.nativeElement.querySelectorAll('lg-toast-stack').length).toBe(1);
        expect(fixture.nativeElement.querySelectorAll('lg-app-header').length).toBe(1);
    });

    describe('the section (ISC-229)', () => {
        it('is named on every top-level route of the real table, as its own path', () => {
            const named = new Map(
                routes.filter(r => r.data?.['section'] !== undefined).map(r => [r.path, r.data!['section']]),
            );
            expect([...named.keys()].sort()).toEqual([...SECTIONS].sort());
            for (const [path, section] of named) expect(section).toBe(path);
        });

        it.each(SECTIONS)('writes data-section="%s" on the host for /%s', async section => {
            const fixture = TestBed.createComponent(AppShell);
            await TestBed.inject(Router).navigateByUrl(`/${section}`);
            fixture.detectChanges();

            expect(fixture.nativeElement.getAttribute('data-section')).toBe(section);
        });

        it.each(['shortlist', 'pipeline'] as const)('inherits %s to the :id child', async section => {
            const fixture = TestBed.createComponent(AppShell);
            await TestBed.inject(Router).navigateByUrl(`/${section}/7`);
            fixture.detectChanges();

            expect(fixture.nativeElement.getAttribute('data-section')).toBe(section);
        });

        it('lands on the dashboard for an unknown path', async () => {
            const fixture = TestBed.createComponent(AppShell);
            await TestBed.inject(Router).navigateByUrl('/nope');
            fixture.detectChanges();

            expect(fixture.nativeElement.getAttribute('data-section')).toBe('dashboard');
        });
    });
});
