import {provideHttpClient} from '@angular/common/http';
import {provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {AppShell} from './app-shell';

describe('AppShell', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
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
});
