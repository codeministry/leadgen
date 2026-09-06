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
            'Lead Generation',
        );
    });

    it('names the logo itself once the wordmark is hidden', () => {
        const fixture = TestBed.createComponent(BrandMark);
        fixture.componentRef.setInput('wordmark', false);
        fixture.detectChanges();

      const mark: HTMLElement = fixture.nativeElement.querySelector('.mark');
      expect(mark.getAttribute('role')).toBe('img');
      expect(mark.getAttribute('aria-label')).toBe('Lead Generation');
      expect(mark.getAttribute('aria-hidden')).toBeNull();
        expect(fixture.nativeElement.querySelector('.wordmark')).toBeNull();
    });

    it('keeps the aspect ratio when the size changes', () => {
        const fixture = TestBed.createComponent(BrandMark);
        fixture.componentRef.setInput('size', 40);
        fixture.detectChanges();

      // A mask has no intrinsic size, so both axes are written on the element; the
      // width is derived from the asset's own 116x128 box and nothing else.
      const mark: HTMLElement = fixture.nativeElement.querySelector('.mark');
      expect(mark.style.height).toBe('40px');
      expect(mark.style.width).toBe('36px');
    });
});
