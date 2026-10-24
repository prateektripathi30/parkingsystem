package com.ridewise.parking.service;

import com.ridewise.parking.model.ParkingModels.ActiveParking;
import com.ridewise.parking.model.ParkingModels.Availability;
import com.ridewise.parking.model.ParkingModels.ParkingReceipt;
import com.ridewise.parking.model.VehicleType;
import com.ridewise.parking.repository.ParkingRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class ParkingService {
    private static final Pattern VALID_PLATE = Pattern.compile("[A-Z0-9 -]{1,20}");
    private final ParkingRepository repository;
    private final Map<VehicleType, Long> hourlyRatesCents;
    private final Clock clock;

    public ParkingService(ParkingRepository repository, Map<VehicleType, Long> hourlyRatesCents, Clock clock) {
        if (!hourlyRatesCents.keySet().containsAll(List.of(VehicleType.values()))
                || hourlyRatesCents.values().stream().anyMatch(rate -> rate == null || rate < 0)) {
            throw new IllegalArgumentException("A non-negative hourly rate is required for every vehicle type");
        }
        this.repository = repository;
        this.hourlyRatesCents = Map.copyOf(hourlyRatesCents);
        this.clock = clock;
    }

    public ActiveParking checkIn(String rawPlate, VehicleType vehicleType) {
        if (vehicleType == null) {
            throw new IllegalArgumentException("Vehicle type is required");
        }
        String plate = normalizePlate(rawPlate);
        long rate = hourlyRatesCents.get(vehicleType);
        return repository.park(plate, vehicleType, clock.instant(), rate);
    }

    public ParkingReceipt checkOut(String rawPlate) {
        return repository.checkOut(normalizePlate(rawPlate), clock.instant());
    }

    public List<Availability> getAvailability() {
        return repository.getAvailability();
    }

    public List<ActiveParking> getActiveVehicles(int limit, int offset) {
        return repository.getActiveVehicles(limit, offset);
    }

    private static String normalizePlate(String rawPlate) {
        if (rawPlate == null) {
            throw new IllegalArgumentException("License plate is required");
        }
        String plate = rawPlate.strip().toUpperCase(Locale.ROOT);
        if (!VALID_PLATE.matcher(plate).matches()) {
            throw new IllegalArgumentException("License plate must be 1-20 letters, digits, spaces, or hyphens");
        }
        return plate;
    }
}
