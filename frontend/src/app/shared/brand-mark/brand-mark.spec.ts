import { TestBed } from '@angular/core/testing';
import { BrandMark } from './brand-mark';

describe('BrandMark', () => {
  it('lets the wordmark carry the name and keeps the logo decorative', () => {
    const fixture = TestBed.createComponent(BrandMark);
    fixture.detectChanges();

    const img: HTMLImageElement = fixture.nativeElement.querySelector('img.mark');
    expect(img.getAttribute('alt')).toBe('');
    expect(img.getAttribute('aria-hidden')).toBe('true');
    expect(fixture.nativeElement.querySelector('.wordmark').getAttribute('aria-label')).toBe(
      'Lead Generation',
    );
  });

  it('names the logo itself once the wordmark is hidden', () => {
    const fixture = TestBed.createComponent(BrandMark);
    fixture.componentRef.setInput('wordmark', false);
    fixture.detectChanges();

    const img: HTMLImageElement = fixture.nativeElement.querySelector('img.mark');
    expect(img.getAttribute('alt')).toBe('Lead Generation');
    expect(img.getAttribute('aria-hidden')).toBeNull();
    expect(fixture.nativeElement.querySelector('.wordmark')).toBeNull();
  });

  it('keeps the aspect ratio when the size changes', () => {
    const fixture = TestBed.createComponent(BrandMark);
    fixture.componentRef.setInput('size', 40);
    fixture.detectChanges();

    const img: HTMLImageElement = fixture.nativeElement.querySelector('img.mark');
    expect(img.style.height).toBe('40px');
    // Intrinsic size stays on the element so the browser reserves the right box
    // before the asset loads; CSS width:auto derives the rest.
    expect(img.getAttribute('width')).toBe('116');
  });
});
