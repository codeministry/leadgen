import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {TranslocoService} from '@jsverse/transloco';
import {WorkflowStage} from '@core/model/workflow';
import de from '../../../../../public/i18n/de.json';
import en from '../../../../../public/i18n/en.json';
import {FLOW_NODE_SIZES} from '../flow-canvas/flow-canvas';
import {FlowNode} from './flow-node';

const FILTER: WorkflowStage = {
    id: 'FILTER',
    kind: 'stage',
    sourceId: null,
    description: 'Judges every offer against the six knockouts.',
    costClasses: ['free'],
    promptId: null,
    settings: [],
    knockouts: [],
    width: null,
};

/**
 * The card's foot keeps its icons and its count on one line, at the size the graph lays it
 * out, with the expand toggle beside it and the longest chip either language writes. The
 * operator saw the chip drop under the icon once the toggle took its room; only a real
 * layout can show a wrap.
 */
describe('FlowNode in a browser', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({providers: [provideRouter([])]});
    });

    it.each(['en', 'de'])('keeps the icons and the count on one line in %s', (lang) => {
        const transloco = TestBed.inject(TranslocoService);
        transloco.setTranslation(lang === 'de' ? de : en, lang);
        transloco.setActiveLang(lang);

        const fixture: ComponentFixture<FlowNode> = TestBed.createComponent(FlowNode);
        fixture.componentRef.setInput('stage', FILTER);
        fixture.componentRef.setInput('phaseId', 'sort');
        fixture.componentRef.setInput('count', 12548);
        fixture.componentRef.setInput('expandable', true);
        const box = document.createElement('div');
        box.style.cssText = `inline-size: ${FLOW_NODE_SIZES.stage.width}px; block-size: ${FLOW_NODE_SIZES.stage.height}px;`;
        document.body.appendChild(box);
        box.appendChild(fixture.nativeElement as HTMLElement);
        fixture.detectChanges();

        const card = (fixture.nativeElement as HTMLElement).querySelector('.flow-node')!.getBoundingClientRect();
        const icons = (fixture.nativeElement as HTMLElement).querySelector('.flow-node-icons')!.getBoundingClientRect();
        const count = (fixture.nativeElement as HTMLElement).querySelector('.flow-node-count')!.getBoundingClientRect();

        expect(Math.abs(count.top + count.height / 2 - (icons.top + icons.height / 2))).toBeLessThan(2);
        expect(count.right).toBeLessThanOrEqual(card.right);
        expect(count.bottom).toBeLessThanOrEqual(card.bottom);
        // …and whole: an ellipsis would hide the verb or the number.
        const chip = (fixture.nativeElement as HTMLElement).querySelector('.flow-node-count') as HTMLElement;
        expect(chip.scrollWidth).toBeLessThanOrEqual(chip.clientWidth);
        box.remove();
    });
});
