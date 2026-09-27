import {TestBed} from '@angular/core/testing';
import {PageHeader} from './page-header';

describe('PageHeader', () => {
    it('renders the title as the page heading and drops an absent subtitle', () => {
        const fixture = TestBed.createComponent(PageHeader);
        fixture.componentRef.setInput('title', 'Shortlist');
        fixture.detectChanges();

        expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Shortlist');
        expect(fixture.nativeElement.querySelector('p')).toBeNull();
    });

  it('takes all three heading levels, so a panel inside a screen keeps the outline', () => {
    // A panel that opens inside a screen sits under that screen's own `h2`. Without the
    // third step the sources panel set `type-h3` on a bare heading by hand — this
    // component's job, done in a template.
    for (const [heading, tag] of [['h1', 'h1'], ['h2', 'h2'], ['h3', 'h3']] as const) {
      const fixture = TestBed.createComponent(PageHeader);
      fixture.componentRef.setInput('title', 'demo-newsletter');
      fixture.componentRef.setInput('heading', heading);
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector(tag)?.textContent).toContain('demo-newsletter');
    }
  });

  it('gives the title the whole row when stacked, and only then', () => {
    // The offer detail's actions wrap under its title anyway; without the full row the
    // title broke after a few words beside the space they left.
    const fixture = TestBed.createComponent(PageHeader);
    fixture.componentRef.setInput('title', 'A long offer title');
    fixture.detectChanges();
    const titleBlock = (): HTMLElement => fixture.nativeElement.querySelector('h1').parentElement;
    expect(titleBlock().classList).not.toContain('basis-full');

    fixture.componentRef.setInput('stacked', true);
    fixture.detectChanges();
    expect(titleBlock().classList).toContain('basis-full');
  });
});
