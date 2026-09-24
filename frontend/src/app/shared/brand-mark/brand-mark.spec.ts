import {TestBed} from '@angular/core/testing';
import {BrandMark} from './brand-mark';

describe('BrandMark', () => {
    it('lets the wordmark carry the name and keeps the logo decorative', () => {
        const fixture = TestBed.createComponent(BrandMark);
        fixture.detectChanges();

      const mark: HTMLElement = fixture.nativeElement.querySelector('.mark');
      expect(mark.getAttribute('role')).toBeNull();
      expect(mark.getAttribute('aria-label')).toBeNull();
      expect(mark.getAttribute('aria-hidden')).toBe('true');
        expect(fixture.nativeElement.querySelector('.wordmark').getAttribute('aria-label')).toBe(
            'Annusa',
        );
    });

    it('names the logo itself once the wordmark is hidden', () => {
        const fixture = TestBed.createComponent(BrandMark);
        fixture.componentRef.setInput('wordmark', false);
        fixture.detectChanges();

      const mark: HTMLElement = fixture.nativeElement.querySelector('.mark');
      expect(mark.getAttribute('role')).toBe('img');
      expect(mark.getAttribute('aria-label')).toBe('Annusa');
      expect(mark.getAttribute('aria-hidden')).toBeNull();
        expect(fixture.nativeElement.querySelector('.wordmark')).toBeNull();
    });

    it('keeps the aspect ratio when the size changes', () => {
        const fixture = TestBed.createComponent(BrandMark);
        fixture.componentRef.setInput('size', 40);
        fixture.detectChanges();

      // Both axes are written on the element so the box is known before the SVG lays out;
      // the width is derived from the mark's own square viewBox and nothing else.
      const mark: HTMLElement = fixture.nativeElement.querySelector('.mark');
      expect(mark.style.height).toBe('40px');
      expect(mark.style.width).toBe('40px');
    });
});
