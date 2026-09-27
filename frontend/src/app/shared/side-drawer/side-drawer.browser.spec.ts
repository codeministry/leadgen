import {Component, viewChild} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {page} from 'vitest/browser';
import {SideDrawer} from './side-drawer';

@Component({
    imports: [SideDrawer],
    template: `
      <button type="button" #trigger (click)="drawer.open(trigger)">Open</button>
      <lg-side-drawer #drawer [dock]="true" labelledBy="drawer-title" width="20rem" (dismissed)="drawer.close()">
        <h2 id="drawer-title">Drawer</h2>
      </lg-side-drawer>
    `,
})
class Host {
    readonly drawer = viewChild.required(SideDrawer);
}

/**
 * A docking drawer that falls back to its modal frame on a resize (fix 5F-2): the `<dialog>` the
 * `@if` stamps after the breakpoint change is a new element, and a backdrop tap on it has to
 * dismiss exactly like one on the dialog the first render held.
 *
 * <p>In a real browser, because the claim rides on `matchMedia` changing with the viewport.
 */
describe('SideDrawer backdrop after a breakpoint change', () => {
    let fixture: ComponentFixture<Host>;

    afterEach(() => (fixture.nativeElement as HTMLElement).remove());

    async function settle(): Promise<void> {
        for (let i = 0; i < 4; i++) {
            await new Promise((resolve) => setTimeout(resolve, 0));
            fixture.detectChanges();
        }
    }

    /** Resized, and `matchMedia` has told the drawer: the frame it stamps follows the breakpoint. */
    async function resize(width: number): Promise<void> {
        await page.viewport(width, 900);
        await vi.waitFor(() => {
            fixture.detectChanges();
            expect(matchMedia('(width >= 48rem)').matches).toBe(width >= 768);
            expect(dialog() === null).toBe(width >= 768);
        });
        await settle();
    }

    async function mount(width: number): Promise<void> {
        await page.viewport(width, 900);
        fixture = TestBed.createComponent(Host);
        document.body.appendChild(fixture.nativeElement);
        await settle();
        (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button')!.click();
        await settle();
    }

    const dialog = () => (fixture.nativeElement as HTMLElement).querySelector<HTMLDialogElement>('dialog');

    /** What the browser dispatches for a tap on `::backdrop`: a click whose target is the dialog itself. */
    async function tapBackdrop(): Promise<void> {
        dialog()!.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true}));
        await settle();
    }

    it('dismisses on a backdrop tap after opening docked at 1440 and narrowing to 390', async () => {
        await mount(1440);
        expect(dialog()).toBeNull();

        await resize(390);
        expect(dialog()?.open).toBe(true);

        await tapBackdrop();
        expect(fixture.componentInstance.drawer().isOpen()).toBe(false);
        expect(dialog()?.open).toBe(false);
    });

    it('dismisses on a backdrop tap after narrow → wide → narrow', async () => {
        await mount(390);
        expect(dialog()?.open).toBe(true);

        await resize(1440);
        expect(dialog()).toBeNull();
        await resize(390);
        expect(dialog()?.open).toBe(true);

        await tapBackdrop();
        expect(fixture.componentInstance.drawer().isOpen()).toBe(false);
        expect(dialog()?.open).toBe(false);
    });

    it('does not dismiss on a click inside the panel', async () => {
        await mount(390);
        (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>('#drawer-title')!.click();
        await settle();
        expect(fixture.componentInstance.drawer().isOpen()).toBe(true);
    });
});
