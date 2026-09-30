import {ChangeDetectionStrategy, Component, computed, inject, input} from '@angular/core';
import {Router, RouterLink} from '@angular/router';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {ChatStatisticsDay, ChatStatisticsSource} from '@core/model/chat';
import {Icon} from '@shared/icon/icon';

/** The sparkline's box: a small inline figure, never a chart with axes. */
const SPARK_W = 128;
const SPARK_H = 28;
const SPARK_PAD = 3;

/** A difference as the table shows it: a sign, a number, and which way it went. */
interface Delta {
    readonly text: string;
    readonly direction: 'up' | 'down' | 'flat';
}

interface Spark {
    readonly points: string;
    readonly lastX: number;
    readonly lastY: number;
}

/**
 * One `statistics` call of an answer, drawn from the tool's own result (ISC-460): a compact table of
 * the headline numbers, a sparkline of the intake per day, and a link to `/analytics` with the window
 * it answered. Nothing here reads the answer's text, so a digit the model wrote cannot reach it.
 *
 * <p>**Neutral ink throughout.** A difference is a sign and an arrow in the row's own colour, never a
 * good or bad colour: whether more intake is good is the reader's call, not the card's. The sparkline
 * is muted `currentColor`, never the signal and never `--lg-ai`. It is `aria-hidden`; the table is the
 * accessible form of the same numbers.
 *
 * <p>**Only the table's wrapper scrolls sideways** at the 390 sheet; the card itself never widens
 * the column it sits in.
 */
@Component({
    selector: 'lg-chat-statistics-card',
    imports: [Icon, RouterLink, TranslocoPipe],
    templateUrl: './statistics-card.html',
    styleUrl: './statistics-card.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatStatisticsCard {
    readonly source = input.required<ChatStatisticsSource>();

    private readonly router = inject(Router);
    private readonly transloco = inject(TranslocoService);

    protected readonly compared = computed(() => this.source().compareFrom !== null && this.source().compareTo !== null);
    protected readonly spark = computed(() => sparkline(this.source().series));
    protected readonly sparkW = SPARK_W;
    protected readonly sparkH = SPARK_H;
    /** The window, and `?chat` as it stands, so the drawer stays beside the analytics it opened. */
    protected readonly query = computed(() => {
        const {from, to} = this.source();
        const chat = this.router.parseUrl(this.router.url).queryParamMap.get('chat');
        return chat === null ? {from, to} : {from, to, chat};
    });

    protected number(value: number | null): string {
        if (value === null) return '—';
        return new Intl.NumberFormat(this.transloco.getActiveLang(), {maximumFractionDigits: 1}).format(value);
    }

    protected delta(value: number | null): Delta | null {
        if (value === null) return null;
        const magnitude = this.number(Math.abs(value));
        if (value > 0) return {text: `+${magnitude}`, direction: 'up'};
        if (value < 0) return {text: `−${magnitude}`, direction: 'down'};
        return {text: magnitude, direction: 'flat'};
    }
}

/** The series as polyline points in the sparkline's box, oldest on the left. */
function sparkline(series: readonly ChatStatisticsDay[]): Spark | null {
    if (series.length === 0) return null;
    const counts = series.map((day) => day.count);
    const min = Math.min(...counts);
    const span = Math.max(...counts) - min || 1;
    const step = series.length === 1 ? 0 : (SPARK_W - 2 * SPARK_PAD) / (series.length - 1);
    const xy = counts.map((count, i) => {
        const x = series.length === 1 ? SPARK_W / 2 : SPARK_PAD + i * step;
        const y = SPARK_H - SPARK_PAD - ((count - min) / span) * (SPARK_H - 2 * SPARK_PAD);
        return [Math.round(x * 10) / 10, Math.round(y * 10) / 10] as const;
    });
    const [lastX, lastY] = xy[xy.length - 1];
    return {points: xy.map(([x, y]) => `${x},${y}`).join(' '), lastX, lastY};
}
