import {formatUsd, formatUsdPrice} from './usd';

describe('formatUsd', () => {
    it('writes a dollar and up with two decimals, and zero as zero', () => {
        expect(formatUsd('3', 'en')).toBe('$3.00');
        expect(formatUsd('15.5', 'en')).toBe('$15.50');
        expect(formatUsd('0', 'en')).toBe('$0.00');
    });

    it('keeps up to four decimals under a dollar, so a paid call never reads as nothing', () => {
        expect(formatUsd('0.012207', 'en')).toBe('$0.0122');
        expect(formatUsd('0.348', 'en')).toBe('$0.348');
        expect(formatUsd('0.5', 'en')).toBe('$0.50');
    });

    it('writes a non-zero amount under the smallest shown one as below it', () => {
        expect(formatUsd('0.00004', 'en')).toBe('< $0.0001');
    });

    it('keeps the currency USD and lets only the separators follow the language', () => {
        expect(formatUsd('0.348', 'de').replace(/\s/g, ' ')).toBe('0,348 $');
    });

    it('passes a string that is no amount through unchanged', () => {
        expect(formatUsd('n/a', 'en')).toBe('n/a');
    });
});

describe('formatUsdPrice', () => {
    it('writes a configured price with every digit it was configured with, never rounded to cents', () => {
        expect(formatUsdPrice('2.505', 'en')).toBe('$2.505');
        expect(formatUsdPrice('1.23456789', 'en')).toBe('$1.23456789');
        expect(formatUsdPrice('0.348', 'en')).toBe('$0.348');
    });

    it('writes at least two decimals, and no trailing zeros beyond them', () => {
        expect(formatUsdPrice('3', 'en')).toBe('$3.00');
        expect(formatUsdPrice('15.5', 'en')).toBe('$15.50');
        expect(formatUsdPrice('2.5000', 'en')).toBe('$2.50');
        expect(formatUsdPrice('0', 'en')).toBe('$0.00');
    });

    it('writes a tiny price as itself, never as below the smallest shown amount', () => {
        expect(formatUsdPrice('0.00001', 'en')).toBe('$0.00001');
        expect(formatUsdPrice('0.0000125', 'en')).toBe('$0.0000125');
    });

    it('keeps the currency USD and lets only the separators follow the language', () => {
        expect(formatUsdPrice('2.505', 'de').replace(/\s/g, ' ')).toBe('2,505 $');
    });

    it('passes a string that is no price through unchanged', () => {
        expect(formatUsdPrice('n/a', 'en')).toBe('n/a');
        expect(formatUsdPrice('', 'en')).toBe('');
    });
});
