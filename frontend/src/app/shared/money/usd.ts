/** The smallest amount written as itself; anything above zero but under it is written as "< $0.0001". */
const SMALLEST = 0.0001;

/**
 * An amount in US dollars, written for the active language: the currency is always USD, and only the
 * separators and the symbol's place follow the language (spec 026, Round 0 Q1).
 *
 * <p>The server sends amounts as decimal strings so no float rounds a cent away on the way; this is the
 * one place the string becomes a number, and only to be written. A dollar and up, and zero, take two
 * decimals; under a dollar up to four, so a paid call never reads as "$0.00"; a non-zero amount under
 * $0.0001 is written as below it. A string that is no amount comes back unchanged — honest, rather than
 * an empty field that would read as "nothing spent".
 */
export function formatUsd(amount: string, lang: string): string {
    const value = Number(amount);
    if (amount.trim() === '' || !Number.isFinite(value)) return amount;
    const write = (n: number, max: number): string =>
        new Intl.NumberFormat(lang, {style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: max}).format(n);
    const size = Math.abs(value);
    if (size === 0 || size >= 1) return write(value, 2);
    if (size < SMALLEST) return `< ${write(SMALLEST, 4)}`;
    return write(value, 4);
}

/** The most fraction digits `Intl.NumberFormat` takes in every engine this app runs in. */
const MAX_FRACTION_DIGITS = 20;

/**
 * A configured price in US dollars — per million tokens — written with the precision it was configured with,
 * never rounded the way {@link formatUsd} rounds a spent amount: `"2.505"` stays `$2.505`, where an amount
 * would read `$2.51`, and a tiny price is written as itself rather than as below `$0.0001`.
 *
 * <p>The server sends a price as a plain decimal string, so its own digits after the point decide the
 * decimals: at least two, as for any amount, and none of its trailing zeros beyond them. The currency is
 * always USD; only the separators and the symbol's place follow the language. A string that is no price
 * comes back unchanged.
 */
export function formatUsdPrice(price: string, lang: string): string {
    const value = Number(price);
    if (price.trim() === '' || !Number.isFinite(value)) return price;
    const fraction = /\.(\d*)$/.exec(price.trim())?.[1].replace(/0+$/, '') ?? '';
    const digits = Math.min(Math.max(fraction.length, 2), MAX_FRACTION_DIGITS);
    return new Intl.NumberFormat(lang, {style: 'currency', currency: 'USD', minimumFractionDigits: digits, maximumFractionDigits: digits}).format(value);
}
