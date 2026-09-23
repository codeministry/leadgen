import {ChangeDetectionStrategy, Component, inject} from '@angular/core';
import {RouterLink} from '@angular/router';
import {TranslocoPipe} from '@jsverse/transloco';
import {injectDispatch} from '@ngrx/signals/events';
import {toastEvents} from '@core/toast/toast.events';
import {TOAST_TONE_CLASS} from '@core/toast/toast.model';
import {ToastStore} from '@core/toast/toast.store';
import {Icon} from '@shared/icon/icon';

/**
 * The one place a toast is painted. Rendered once, by the shell, outside the measure.
 *
 * <p>It reads the store and dispatches exactly one thing, `dismissed`, from the close
 * button. The hold and the release are the pointer's and the focus's, and go to the
 * store's timer; navigation is a `routerLink`. Nothing here writes, which is the whole of
 * "a toast carries a link and never an undo".
 */
@Component({
    selector: 'lg-toast-stack',
    imports: [Icon, RouterLink, TranslocoPipe],
    templateUrl: './toast-stack.html',
    styleUrl: './toast-stack.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ToastStack {
    protected readonly store = inject(ToastStore);
    private readonly dispatch = injectDispatch(toastEvents);
    protected readonly toneClass = TOAST_TONE_CLASS;

    protected dismiss(id: number): void {
        this.dispatch.dismissed(id);
    }

    protected hold(id: number): void {
        this.dispatch.held(id);
    }

    protected release(id: number): void {
        this.dispatch.released(id);
    }
}
