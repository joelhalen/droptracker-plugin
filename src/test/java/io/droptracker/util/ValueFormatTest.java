package io.droptracker.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins the event-progress number formatting. A regression here silently
 * mis-renders every HUD progress bar and panel "have / need" row.
 */
public class ValueFormatTest {

    @Test
    public void commasGroupsThousands() {
        assertEquals("500", ValueFormat.commas(500));
        assertEquals("9,999", ValueFormat.commas(9_999));
        assertEquals("10,000,000", ValueFormat.commas(10_000_000));
        assertEquals("0", ValueFormat.commas(0));
        assertEquals("-1,234", ValueFormat.commas(-1_234));
    }

    @Test
    public void abbrevLeavesSmallValuesGrouped() {
        // Below the 10K threshold, abbrev falls through to comma grouping.
        assertEquals("500", ValueFormat.abbrev(500));
        assertEquals("9,999", ValueFormat.abbrev(9_999));
    }

    @Test
    public void abbrevUsesThousandsSuffix() {
        assertEquals("10K", ValueFormat.abbrev(10_000));
        assertEquals("813.6K", ValueFormat.abbrev(813_636));
        assertEquals("100K", ValueFormat.abbrev(100_000));
    }

    @Test
    public void abbrevUsesMillionsSuffix() {
        assertEquals("1M", ValueFormat.abbrev(1_000_000));
        assertEquals("1.25M", ValueFormat.abbrev(1_250_000));
        assertEquals("10M", ValueFormat.abbrev(10_000_000));
    }

    @Test
    public void abbrevUsesBillionsSuffix() {
        assertEquals("1B", ValueFormat.abbrev(1_000_000_000L));
        assertEquals("2.15B", ValueFormat.abbrev(2_147_483_647L));
    }

    @Test
    public void abbrevHandlesNegativesByMagnitude() {
        assertEquals("-1M", ValueFormat.abbrev(-1_000_000));
        assertEquals("-2.5M", ValueFormat.abbrev(-2_500_000));
    }

    @Test
    public void progressGroupsWhenBothSidesAreSmall() {
        assertEquals("50 / 100", ValueFormat.progress(50, 100));
        assertEquals("0 / 99,999", ValueFormat.progress(0, 99_999));
    }

    @Test
    public void progressAbbreviatesWhenEitherSideIsLarge() {
        assertEquals("5 / 100K", ValueFormat.progress(5, 100_000));
        assertEquals("150K / 200K", ValueFormat.progress(150_000, 200_000));
        assertEquals("5M / 10M", ValueFormat.progress(5_000_000, 10_000_000));
    }
}
