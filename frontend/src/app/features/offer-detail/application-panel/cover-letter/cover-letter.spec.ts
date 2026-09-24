import {ComponentFixture, TestBed} from '@angular/core/testing';
import {ApplicationStatus} from '@core/model/application';
import {CoverLetter} from '@core/model/cover-letter';
import {CoverLetterSection} from './cover-letter';

const LETTER: CoverLetter = {
    text: 'Guten Tag Frau Beispiel,\n\nSpring Boot und Kafka habe ich zuletzt bei …',
    author: 'model',
    at: '2026-09-24T08:00:00Z',
};

/**
 * ISC-259 and ISC-260, the web half: the letter is on screen for a packaged application,
 * an edit goes out through `saved`, a redraft through `regenerate`, and from SENT on both
 * are refused where the server would refuse them.
 */
describe('CoverLetterSection', () => {
    function render(
        status: ApplicationStatus,
        letter: CoverLetter | null = LETTER,
    ): ComponentFixture<CoverLetterSection> {
        const fixture = TestBed.createComponent(CoverLetterSection);
        fixture.componentRef.setInput('status', status);
        fixture.componentRef.setInput('letter', letter);
        fixture.detectChanges();
        return fixture;
    }

    function textarea(fixture: ComponentFixture<CoverLetterSection>): HTMLTextAreaElement | null {
        return fixture.nativeElement.querySelector('textarea');
    }

    function button(fixture: ComponentFixture<CoverLetterSection>, css: string): HTMLButtonElement {
        return fixture.nativeElement.querySelector(css) as HTMLButtonElement;
    }

    function type(fixture: ComponentFixture<CoverLetterSection>, text: string): void {
        const area = textarea(fixture)!;
        area.value = text;
        area.dispatchEvent(new Event('input'));
        fixture.detectChanges();
    }

    it('shows the letter of a packaged application, with who wrote it', () => {
        const fixture = render('PACKAGED');

        expect(textarea(fixture)?.value).toBe(LETTER.text);
        expect(fixture.nativeElement.querySelector('lg-badge')?.textContent).toContain('Model draft');
    });

    it('is absent before the application is packaged', () => {
        for (const status of ['NEW', 'SHORTLISTED'] as const) {
            const fixture = render(status, null);
            expect(textarea(fixture)).toBeNull();
            expect(fixture.nativeElement.textContent.trim()).toBe('');
        }
    });

    it('offers Save only once the text differs, and hands the edit out', () => {
        const fixture = render('PACKAGED');
        const saved: string[] = [];
        fixture.componentInstance.saved.subscribe((text) => saved.push(text));

        expect(button(fixture, '.letter-save').disabled).toBe(true);

        type(fixture, 'Guten Tag,\n\nkurz und ohne Floskeln.');
        expect(button(fixture, '.letter-save').disabled).toBe(false);

        button(fixture, '.letter-save').click();
        expect(saved).toEqual(['Guten Tag,\n\nkurz und ohne Floskeln.']);
    });

    it('asks for a fresh draft from the Regenerate button', () => {
        const fixture = render('PACKAGED');
        let asked = 0;
        fixture.componentInstance.regenerate.subscribe(() => (asked += 1));

        button(fixture, '.letter-regenerate').click();
        expect(asked).toBe(1);
    });

    it('is read-only once the application is sent, with both actions disabled', () => {
        for (const status of ['SENT', 'INTERVIEW', 'LOST'] as const) {
            const fixture = render(status);

            expect(textarea(fixture)?.readOnly).toBe(true);
            expect(button(fixture, '.letter-save').disabled).toBe(true);
            expect(button(fixture, '.letter-regenerate').disabled).toBe(true);
        }
    });

    it('holds both actions while a save or a draft is in flight', () => {
        const fixture = render('PACKAGED');
        fixture.componentRef.setInput('drafting', true);
        fixture.detectChanges();

        expect(button(fixture, '.letter-regenerate').disabled).toBe(true);
        expect(button(fixture, '.letter-save').disabled).toBe(true);
    });

    it('says why a write was refused, beside the controls', () => {
        const fixture = render('PACKAGED');
        fixture.componentRef.setInput('error', 'error.letterBudget');
        fixture.detectChanges();

        expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('budget');
    });
});
