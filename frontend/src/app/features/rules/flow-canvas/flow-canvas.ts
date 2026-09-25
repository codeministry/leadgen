import {ChangeDetectionStrategy, Component, computed, input, signal} from '@angular/core';
import {WorkflowView} from '@core/model/workflow';
import {Edge, HtmlTemplateNode, Vflow} from 'ngx-vflow';
import {layoutWorkflow, WorkflowLayoutSizes} from '../workflow-layout';

/** What one node template draws: its label and the px box the layout placed it with. */
interface FlowNodeData {
    readonly label: string;
    readonly width: number;
    readonly height: number;
}

/**
 * The px box per node kind. In px rather than rem because dagre lays out in px and ngx-vflow
 * html nodes ignore their own width/height; the template binds these on the node itself.
 */
export const FLOW_NODE_SIZES: WorkflowLayoutSizes = {stage: {width: 160, height: 56}};

/**
 * The workflow from `/api/v1/workflow` as a left-to-right graph: every ingest source is its
 * own node feeding DEDUPE, and the stages follow in the server's order. The positions come
 * from `layoutWorkflow`; this component only hands them to ngx-vflow.
 */
@Component({
    selector: 'lg-flow-canvas',
    imports: [Vflow],
    templateUrl: './flow-canvas.html',
    styleUrl: './flow-canvas.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FlowCanvas {
    readonly workflow = input.required<WorkflowView>();

    private readonly layout = computed(() => layoutWorkflow(this.workflow(), new Set<string>(), FLOW_NODE_SIZES));

    readonly nodes = computed((): HtmlTemplateNode<FlowNodeData>[] => {
        const stages = new Map(this.workflow().phases.flatMap((phase) => phase.stages.map((stage) => [stage.id, stage])));
        return this.layout().nodes.map((node) => ({
            id: node.id,
            type: 'html-template',
            point: signal({x: node.x, y: node.y}),
            width: signal(node.width),
            height: signal(node.height),
            draggable: signal(false),
            // The source id for an ingest entry, the stage id otherwise; catalog labels come later.
            data: signal({label: stages.get(node.stageId)?.sourceId ?? node.stageId, width: node.width, height: node.height}),
        }));
    });

    readonly edges = computed((): Edge[] =>
        this.layout().edges.map((edge) => ({id: edge.id, source: edge.source, target: edge.target})),
    );
}
