package com.ridewise.parking;

import com.ridewise.parking.db.Database;
import com.ridewise.parking.exception.ParkingException;
import com.ridewise.parking.model.ParkingModels.ActiveParking;
import com.ridewise.parking.model.ParkingModels.Availability;
import com.ridewise.parking.model.ParkingModels.ParkingReceipt;
import com.ridewise.parking.model.VehicleType;
import com.ridewise.parking.repository.ParkingRepository;
import com.ridewise.parking.service.FeeCalculator;
import com.ridewise.parking.service.ParkingService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public final class ParkingApplication {
    private static final System.Logger LOGGER = System.getLogger(ParkingApplication.class.getName());
    private final ParkingService parkingService;

    private ParkingApplication(ParkingService parkingService) {
        this.parkingService = parkingService;
    }

    public static void main(String[] args) {
        Properties properties = Database.loadDefaultProperties();
        try (Database database = new Database(properties)) {
            int floors = Database.integerProperty(properties, "lot.floor-count", 3);
            Map<String, Integer> spotCounts = Map.of(
                    "MOTORCYCLE", nonNegativeProperty(properties, "lot.motorcycle-spots-per-floor", 4),
                    "COMPACT", nonNegativeProperty(properties, "lot.compact-spots-per-floor", 10),
                    "LARGE", nonNegativeProperty(properties, "lot.large-spots-per-floor", 8),
                    "BUS", nonNegativeProperty(properties, "lot.bus-spots-per-floor", 2));
            database.initialize(floors, spotCounts);

            Map<VehicleType, Long> rates = new LinkedHashMap<>();
            rates.put(VehicleType.MOTORCYCLE, nonNegativeLongProperty(properties, "fee.motorcycle-hourly-cents", 100));
            rates.put(VehicleType.CAR, nonNegativeLongProperty(properties, "fee.car-hourly-cents", 300));
            rates.put(VehicleType.BUS, nonNegativeLongProperty(properties, "fee.bus-hourly-cents", 800));

            ParkingApplication application = new ParkingApplication(
                    new ParkingService(new ParkingRepository(database, new FeeCalculator()), rates, Clock.systemUTC()));
            application.run();
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "Parking system could not start: {0}", exception.getMessage());
            throw exception;
        }
    }

    private void run() {
        System.out.println("Smart Parking System");
        System.out.println("Rates are charged per started hour; a zero-minute stay is free.");
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
            boolean running = true;
            while (running) {
                printMenu();
                String selection = input.readLine();
                if (selection == null) {
                    break;
                }
                try {
                    switch (selection.strip()) {
                        case "1" -> checkIn(input);
                        case "2" -> checkOut(input);
                        case "3" -> showAvailability();
                        case "4" -> showActiveVehicles(input);
                        case "5" -> running = false;
                        default -> System.out.println("Invalid selection. Choose 1-5.");
                    }
                } catch (ParkingException | IllegalArgumentException exception) {
                    LOGGER.log(System.Logger.Level.WARNING, "Request rejected: {0}", exception.getMessage());
                    System.out.println("Unable to complete request: " + exception.getMessage());
                } catch (IOException exception) {
                    LOGGER.log(System.Logger.Level.ERROR, "Input/output failure", exception);
                    System.out.println("Input error. Please restart the application.");
                    running = false;
                }
            }
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "Could not read console input", exception);
        }
        System.out.println("Parking system stopped.");
    }

    private void checkIn(BufferedReader input) throws IOException {
        System.out.print("License plate: ");
        String plate = input.readLine();
        if (plate == null) {
            return;
        }
        System.out.print("Vehicle type (MOTORCYCLE/CAR/BUS): ");
        String type = input.readLine();
        if (type == null) {
            return;
        }
        VehicleType vehicleType;
        try {
            vehicleType = VehicleType.valueOf(type.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Vehicle type must be MOTORCYCLE, CAR, or BUS");
        }
        ActiveParking parked = parkingService.checkIn(plate, vehicleType);
        System.out.printf("Parked %s on floor %d, spot %d (%s). Entry: %s%n",
                parked.licensePlate(), parked.spot().floorNumber(), parked.spot().spotNumber(),
                parked.spot().spotType(), parked.entryTime());
    }

    private void checkOut(BufferedReader input) throws IOException {
        System.out.print("License plate: ");
        String plate = input.readLine();
        if (plate == null) {
            return;
        }
        ParkingReceipt receipt = parkingService.checkOut(plate);
        System.out.printf("Checked out %s from floor %d, spot %d. Duration: %d minute(s). Fee: %s%n",
                receipt.licensePlate(), receipt.spot().floorNumber(), receipt.spot().spotNumber(),
                receipt.durationMinutes(), formatMoney(receipt.chargedCents()));
    }

    private void showAvailability() {
        List<Availability> availability = parkingService.getAvailability();
        if (availability.isEmpty()) {
            System.out.println("No parking spots are configured.");
            return;
        }
        System.out.println("Available spots:");
        availability.forEach(item -> System.out.printf("  Floor %d | %-11s | %d%n",
                item.floorNumber(), item.spotType(), item.availableCount()));
    }

    private void showActiveVehicles(BufferedReader input) throws IOException {
        final int pageSize = 100;
        int offset = 0;
        while (true) {
            List<ActiveParking> activeVehicles = parkingService.getActiveVehicles(pageSize, offset);
            if (offset == 0 && activeVehicles.isEmpty()) {
                System.out.println("No vehicles are currently parked.");
                return;
            }
            activeVehicles.forEach(vehicle -> System.out.printf(
                    "  %s | %-10s | floor %d spot %d | entered %s%n",
                    vehicle.licensePlate(), vehicle.vehicleType(), vehicle.spot().floorNumber(),
                    vehicle.spot().spotNumber(), vehicle.entryTime()));
            if (activeVehicles.size() < pageSize) {
                return;
            }
            System.out.println("Displayed " + (offset + activeVehicles.size()) + " active vehicle(s).");
            System.out.print("Show the next page? (y/n): ");
            String nextPage = input.readLine();
            if (nextPage == null || !nextPage.strip().equalsIgnoreCase("y")) {
                return;
            }
            offset += pageSize;
        }
    }

    private static void printMenu() {
        System.out.println();
        System.out.println("1. Check in vehicle");
        System.out.println("2. Check out vehicle");
        System.out.println("3. Show availability");
        System.out.println("4. List parked vehicles");
        System.out.println("5. Exit");
        System.out.print("Select an option: ");
    }

    private static String formatMoney(long cents) {
        return String.format("$%d.%02d", cents / 100, cents % 100);
    }

    private static int nonNegativeProperty(Properties properties, String key, int defaultValue) {
        String value = System.getProperty(key, properties.getProperty(key));
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(key + " cannot be negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be a non-negative integer", exception);
        }
    }

    private static long nonNegativeLongProperty(Properties properties, String key, long defaultValue) {
        String value = System.getProperty(key, properties.getProperty(key));
        if (value == null) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(key + " cannot be negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be a non-negative amount in cents", exception);
        }
    }
}
