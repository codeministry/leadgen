/**
 * The colour a cell takes by what its value means: healthy, due, failed, information. Never
 * the signal and never a section — both of those already mean something else.
 */
export type Tone = 'neutral' | 'primary' | 'info' | 'success' | 'warning' | 'error';

/**
 * Tone to class, spelled out: Tailwind emits only the class names it finds as literals in the
 * source, so `'lg-tone-' + tone` would leave every tone but the ones written somewhere else
 * without a rule. The classes live in `styles/primitives.css`.
 */
export const TONE_CLASS: Readonly<Record<Tone, string>> = {
    neutral: 'lg-tone-neutral',
    primary: 'lg-tone-primary',
    info: 'lg-tone-info',
    success: 'lg-tone-success',
    warning: 'lg-tone-warning',
    error: 'lg-tone-error',
};
