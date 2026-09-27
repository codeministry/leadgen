import {ComponentFixture, TestBed} from '@angular/core/testing';
import {FunnelView} from '@core/model/funnel';
import {WorkflowStage} from '@core/model/workflow';
import {StageSheet} from './stage-sheet';

const FUNNEL: FunnelView = {
    total: 530,
    stages: [
        {id: 'remote', label: 'Remote', removed: 100},
        {id: 'abroad', label: 'Abroad', removed: 54},
        {id: 'rate', label: 'Rate', removed: 0},
        {id: 'stack', label: 'Stack', removed: 200},
        {id: 'excluded', label: 'Excluded', removed: 50},
        {id: 'duplicate', label: 'Duplicate', removed: 50},
    ],
    survived: 76,
    archived: 0,
};

const FILTER: WorkflowStage = {
    id: 'FILTER',
    kind: 'stage',
    sourceId: null,
    description: 'What FILTER does.',
    costClasses: ['free'],
    promptId: null,
    settings: [{key: 'remote.accept_unknown', value: 'true', file: 'matching-rules.yaml'}],
    knockouts: null,
    width: null,
};

/**
 * ISC-393: the sheet is the overlay the selected stage opens in. It hosts `lg-stage-detail`
 * unchanged, takes focus on its heading, and closes by its button or Escape — it never
 * navigates itself; the screen owns the `stage` parameter.
 */
describe('StageSheet', () => {
    let fixture: ComponentFixture<StageSheet>;
    let closed: number;

    async function render(stage: WorkflowStage | 'unread' = FILTER): Promise<HTMLElement> {
        fixture = TestBed.createComponent(StageSheet);
        fixture.componentRef.setInput('stage', stage);
        fixture.componentRef.setInput('funnel', FUNNEL);
        closed = 0;
        fixture.componentInstance.closed.subscribe(() => closed++);
        const host = fixture.nativeElement as HTMLElement;
        // Focus only lands on an element that is in the document.
        document.body.appendChild(host);
        fixture.detectChanges();
        await fixture.whenStable();
        return host;
    }

    afterEach(() => (fixture.nativeElement as HTMLElement).remove());

    it('is a labelled, non-modal dialog: the canvas behind it stays usable', async () => {
        const host = await render();
        const dialog = host.querySelector('[role="dialog"]');
        const heading = host.querySelector('.sheet-heading');

        expect(dialog).not.toBeNull();
        expect(dialog?.getAttribute('aria-modal')).toBe('false');
        expect(heading?.id).toBeTruthy();
        expect(dialog?.getAttribute('aria-labelledby')).toBe(heading?.id);
        expect(heading?.textContent?.trim()).toBe('Stage settings');
        expect(dialog?.classList.contains('lg-panel')).toBe(true);
    });

    it('hosts the stage detail with the inputs it was given', async () => {
        const host = await render();
        const detail = host.querySelector('lg-stage-detail');

        expect(detail).not.toBeNull();
        expect(detail?.querySelector('lg-funnel-rail')).not.toBeNull();
        // Settings grouped by the file they come from, each key by its last segment.
        expect(detail?.querySelector('[data-section="settings"] .settings-file-name')?.textContent?.trim()).toBe('matching-rules.yaml');
        expect(detail?.querySelector('[data-section="settings"]')?.textContent).toContain('accept_unknown');
    });

    it('moves focus to its heading once it has rendered', async () => {
        const host = await render();
        const heading = host.querySelector<HTMLElement>('.sheet-heading');

        expect(heading?.getAttribute('tabindex')).toBe('-1');
        expect(document.activeElement).toBe(heading);
    });

    it('emits closed from its labelled close button', async () => {
        const host = await render();
        const button = host.querySelector<HTMLButtonElement>('button[data-action="close"]');

        expect(button?.getAttribute('aria-label')).toBe('Close stage settings');
        expect(button?.classList.contains('btn-ghost')).toBe(true);
        button?.click();
        expect(closed).toBe(1);
    });

    it('emits closed on Escape pressed anywhere inside it', async () => {
        const host = await render();
        const inside = host.querySelector('lg-stage-detail') as HTMLElement;

        inside.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}));
        expect(closed).toBe(1);

        inside.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true}));
        expect(closed).toBe(1);
    });

    it('opens the unread entry as well', async () => {
        const host = await render('unread');

        expect(host.querySelector('[role="dialog"] lg-stage-detail')).not.toBeNull();
    });
});
