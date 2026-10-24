package com.ridewise.parking.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeeCalculatorTest {
    private final FeeCalculator feeCalculator = new FeeCalculator();
    private final Instant start = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void zeroLengthStayIsFree() {
        assertEquals(0, feeCalculator.calculateCents(start, start, 300));
    }

    @Test
    void partialHourRoundsUpAndExactHourDoesNotAddAnother() {
        assertEquals(300, feeCalculator.calculateCents(start, start.plusSeconds(1), 300));
        assertEquals(300, feeCalculator.calculateCents(start, start.plusSeconds(3600), 300));
        assertEquals(600, feeCalculator.calculateCents(start, start.plusSeconds(3601), 300));
    }

    @Test
    void rejectsInvalidDurationAndRate() {
        assertThrows(IllegalArgumentException.class,
                () -> feeCalculator.calculateCents(start, start.minusSeconds(1), 300));
        assertThrows(IllegalArgumentException.class,
                () -> feeCalculator.calculateCents(start, start, -1));
    }

    @Test
    void detectsChargeOverflow() {
        assertThrows(ArithmeticException.class,
                () -> feeCalculator.calculateCents(start, start.plusSeconds(7200), Long.MAX_VALUE));
    }
}
