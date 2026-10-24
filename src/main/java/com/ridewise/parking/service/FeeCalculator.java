package com.ridewise.parking.service;

import java.time.Duration;
import java.time.Instant;

public final class FeeCalculator {
    private static final long MILLIS_PER_HOUR = Duration.ofHours(1).toMillis();
    private static final long MILLIS_PER_MINUTE = Duration.ofMinutes(1).toMillis();

    public long calculateCents(Instant entryTime, Instant exitTime, long hourlyRateCents) {
        if (entryTime == null || exitTime == null || exitTime.isBefore(entryTime)) {
            throw new IllegalArgumentException("Exit time must not be before entry time");
        }
        if (hourlyRateCents < 0) {
            throw new IllegalArgumentException("Hourly rate cannot be negative");
        }
        long durationMillis = Duration.between(entryTime, exitTime).toMillis();
        long billableHours = durationMillis == 0 ? 0
                : durationMillis / MILLIS_PER_HOUR + (durationMillis % MILLIS_PER_HOUR == 0 ? 0 : 1);
        return Math.multiplyExact(billableHours, hourlyRateCents);
    }

    public long durationMinutes(Instant entryTime, Instant exitTime) {
        long durationMillis = Duration.between(entryTime, exitTime).toMillis();
        return durationMillis / MILLIS_PER_MINUTE + (durationMillis % MILLIS_PER_MINUTE == 0 ? 0 : 1);
    }
}
