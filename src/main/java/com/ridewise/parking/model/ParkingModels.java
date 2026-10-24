package com.ridewise.parking.model;

import java.time.Instant;

public final class ParkingModels {
    private ParkingModels() {
    }

    public record SpotAssignment(long spotId, int floorNumber, int spotNumber, SpotType spotType) {
    }

    public record ActiveParking(
            long sessionId,
            String licensePlate,
            VehicleType vehicleType,
            SpotAssignment spot,
            Instant entryTime) {
    }

    public record ParkingReceipt(
            long sessionId,
            String licensePlate,
            VehicleType vehicleType,
            SpotAssignment spot,
            Instant entryTime,
            Instant exitTime,
            long durationMinutes,
            long chargedCents) {
    }

    public record Availability(int floorNumber, SpotType spotType, long availableCount) {
    }
}
