import {
  Ban,
  Building2,
  CalendarDays,
  ChevronDown,
  ChevronRight,
  CircleAlert,
  CircleCheck,
  Clock,
  Columns3,
  Copy,
  Database,
  Download,
  ExternalLink,
  FileText,
  Funnel,
  Inbox,
  LayoutDashboard,
  ListChecks,
  MapPin,
  Monitor,
  Moon,
  PanelLeft,
  RefreshCw,
  Search,
  SlidersHorizontal,
  Sun,
  TriangleAlert,
  X,
} from 'lucide';

/** Lucide 1.x hands out a flat `[tag, attributes][]`; there are no nested children. */
export type IconPart = readonly [string, Record<string, string | number | undefined>];
export type IconNode = readonly IconPart[];

/**
 * One named import per icon, because `lucide` is side-effect-free ESM and a named
 * import is the only shape esbuild can tree-shake — a wildcard would pull all
 * ~1600 icons into the bundle.
 *
 * `lucide-angular` would be the obvious choice and is not usable here: version
 * 1.0.0 pins `@angular/core` to `13.x - 21.x`, which excludes this repo's 22.
 * Everything Angular-facing therefore lives in `icon.ts`, and swapping back later
 * touches these two files and no template.
 */
export const LG_ICONS = {
  ban: Ban,
  'building-2': Building2,
  'calendar-days': CalendarDays,
  'chevron-down': ChevronDown,
  'chevron-right': ChevronRight,
  'circle-alert': CircleAlert,
  'circle-check': CircleCheck,
  clock: Clock,
  'columns-3': Columns3,
  copy: Copy,
  database: Database,
  download: Download,
  'external-link': ExternalLink,
  'file-text': FileText,
  funnel: Funnel,
  inbox: Inbox,
  'layout-dashboard': LayoutDashboard,
  'list-checks': ListChecks,
  'map-pin': MapPin,
  monitor: Monitor,
  moon: Moon,
  'panel-left': PanelLeft,
  refresh: RefreshCw,
  search: Search,
  'sliders-horizontal': SlidersHorizontal,
  sun: Sun,
  'triangle-alert': TriangleAlert,
  x: X,
} as const satisfies Record<string, IconNode>;

export type LgIconName = keyof typeof LG_ICONS;
