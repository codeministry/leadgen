import {ComponentFixture, TestBed} from '@angular/core/testing';
import {ApplicationStatus} from '@core/model/application';
import {CoverLetter} from '@core/model/cover-letter';
import {CoverLetterSection} from './cover-letter';

const LETTER: CoverLetter = {
    text: 'Guten Tag Frau Beispiel,\n\nSpring Boot und Kafka habe ich zuletzt bei …',
    author: 'model',
    at: '2026-09-24T08:00:00Z',
    frozen: false,
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

    function view(fixture: ComponentFixture<CoverLetterSection>): HTMLElement | null {
        return fixture.nativeElement.querySelector('.letter-view');
    }

    function openEditor(fixture: ComponentFixture<CoverLetterSection>): void {
        button(fixture, '.letter-edit').click();
        fixture.detectChanges();
    }

    function type(fixture: ComponentFixture<CoverLetterSection>, text: string): void {
        const area = textarea(fixture)!;
        area.value = text;
        area.dispatchEvent(new Event('input'));
        fixture.detectChanges();
    }

    it('shows the letter as it reads, ready to copy, with who wrote it', () => {
        const fixture = render('PACKAGED');

        expect(textarea(fixture)).toBeNull();
        expect(view(fixture)?.querySelectorAll('p').length).toBe(2);
        expect(view(fixture)?.textContent).toContain('Spring Boot und Kafka');
        expect(button(fixture, '.letter-copy')).not.toBeNull();
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
        openEditor(fixture);
        expect(textarea(fixture)?.value).toBe(LETTER.text);

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

    it('offers only Copy once the server says the letter went out', () => {
        // The server's reading decides; the restored-at-NEW case is the next spec.
        for (const status of ['SENT', 'INTERVIEW', 'LOST'] as const) {
            const fixture = render(status, {...LETTER, frozen: true});

            expect(view(fixture)).not.toBeNull();
            expect(button(fixture, '.letter-copy')).not.toBeNull();
            expect(button(fixture, '.letter-edit')).toBeNull();
            expect(button(fixture, '.letter-regenerate')).toBeNull();
        }
    });

    it('shows the letter of a package even at NEW, and no "went out" note above an empty letter', () => {
        const restored = TestBed.createComponent(CoverLetterSection);
        restored.componentRef.setInput('status', 'NEW');
        restored.componentRef.setInput('hasPackage', true);
        restored.componentRef.setInput('letter', {...LETTER, frozen: true});
        restored.detectChanges();
        expect(view(restored)).not.toBeNull();

        const rejected = render('REJECTED', null);
        expect(rejected.nativeElement.textContent).not.toContain('coverLetter.noteSent');
    });

    it('holds both actions while a save or a draft is in flight', () => {
        const fixture = render('PACKAGED');
        fixture.componentRef.setInput('drafting', true);
        fixture.detectChanges();

        expect(button(fixture, '.letter-regenerate').disabled).toBe(true);
        expect(button(fixture, '.letter-edit').disabled).toBe(true);
    });

    it('says why a write was refused, beside the controls', () => {
        const fixture = render('PACKAGED');
        fixture.componentRef.setInput('error', 'error.letterBudget');
        fixture.detectChanges();

        expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('budget');
    });
});
