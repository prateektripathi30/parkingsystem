package com.ridewise.parking.repository;

import com.ridewise.parking.db.Database;
import com.ridewise.parking.exception.AlreadyParkedException;
import com.ridewise.parking.exception.NoAvailableSpotException;
import com.ridewise.parking.exception.NotParkedException;
import com.ridewise.parking.exception.ParkingException;
import com.ridewise.parking.model.ParkingModels.ActiveParking;
import com.ridewise.parking.model.ParkingModels.Availability;
import com.ridewise.parking.model.ParkingModels.ParkingReceipt;
import com.ridewise.parking.model.ParkingModels.SpotAssignment;
import com.ridewise.parking.model.SpotType;
import com.ridewise.parking.model.VehicleType;
import com.ridewise.parking.service.FeeCalculator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public final class ParkingRepository {
    private static final System.Logger LOGGER = System.getLogger(ParkingRepository.class.getName());
    private final Database database;
    private final FeeCalculator feeCalculator;

    public ParkingRepository(Database database, FeeCalculator feeCalculator) {
        this.database = database;
        this.feeCalculator = feeCalculator;
    }

    public ActiveParking park(String plate, VehicleType vehicleType, Instant entryTime, long hourlyRateCents) {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement existingVehicle = connection.prepareStatement(
                        "SELECT 1 FROM active_vehicles WHERE license_plate = ?")) {
                    existingVehicle.setString(1, plate);
                    try (ResultSet result = existingVehicle.executeQuery()) {
                        if (result.next()) {
                            throw new AlreadyParkedException("Vehicle " + plate + " is already parked");
                        }
                    }
                }
                SpotAssignment spot = allocateSpot(connection, vehicleType);
                try (PreparedStatement updateSpot = connection.prepareStatement(
                        "UPDATE parking_spots SET occupied = TRUE WHERE id = ? AND occupied = FALSE")) {
                    updateSpot.setLong(1, spot.spotId());
                    if (updateSpot.executeUpdate() != 1) {
                        throw new ParkingException("Selected parking spot became unavailable; please retry");
                    }
                }

                long sessionId;
                try (PreparedStatement insertSession = connection.prepareStatement(
                        "INSERT INTO parking_sessions(license_plate, vehicle_type, spot_id, entry_time_ms, "
                                + "hourly_rate_cents) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    insertSession.setString(1, plate);
                    insertSession.setString(2, vehicleType.name());
                    insertSession.setLong(3, spot.spotId());
                    insertSession.setLong(4, entryTime.toEpochMilli());
                    insertSession.setLong(5, hourlyRateCents);
                    insertSession.executeUpdate();
                    try (ResultSet keys = insertSession.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("Database did not return a parking session ID");
                        }
                        sessionId = keys.getLong(1);
                    }
                }

                try (PreparedStatement registerVehicle = connection.prepareStatement(
                        "INSERT INTO active_vehicles(license_plate, session_id) VALUES (?, ?)")) {
                    registerVehicle.setString(1, plate);
                    registerVehicle.setLong(2, sessionId);
                    try {
                        registerVehicle.executeUpdate();
                    } catch (SQLException exception) {
                        if ("23505".equals(exception.getSQLState())) {
                            throw new AlreadyParkedException("Vehicle " + plate + " is already parked");
                        }
                        throw exception;
                    }
                }
                connection.commit();
                LOGGER.log(System.Logger.Level.INFO, "Vehicle {0} parked at floor {1}, spot {2}",
                        plate, spot.floorNumber(), spot.spotNumber());
                return new ActiveParking(sessionId, plate, vehicleType, spot, entryTime);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new ParkingException("Database error while parking vehicle " + plate, exception);
        }
    }

    public ParkingReceipt checkOut(String plate, Instant exitTime) {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long sessionId;
                try (PreparedStatement active = connection.prepareStatement(
                        "SELECT session_id FROM active_vehicles WHERE license_plate = ? FOR UPDATE")) {
                    active.setString(1, plate);
                    try (ResultSet result = active.executeQuery()) {
                        if (!result.next()) {
                            throw new NotParkedException("Vehicle " + plate + " is not currently parked");
                        }
                        sessionId = result.getLong("session_id");
                    }
                }

                Instant entryTime;
                VehicleType vehicleType;
                long rate;
                SpotAssignment spot;
                String query = "SELECT s.entry_time_ms, s.vehicle_type, s.hourly_rate_cents, "
                        + "p.id, p.floor_number, p.spot_number, p.spot_type "
                        + "FROM parking_sessions s JOIN parking_spots p ON p.id = s.spot_id WHERE s.id = ?";
                try (PreparedStatement details = connection.prepareStatement(query)) {
                    details.setLong(1, sessionId);
                    try (ResultSet result = details.executeQuery()) {
                        if (!result.next()) {
                            throw new ParkingException("Active vehicle has no matching parking session");
                        }
                        entryTime = Instant.ofEpochMilli(result.getLong("entry_time_ms"));
                        vehicleType = VehicleType.valueOf(result.getString("vehicle_type"));
                        rate = result.getLong("hourly_rate_cents");
                        spot = new SpotAssignment(
                                result.getLong("id"),
                                result.getInt("floor_number"),
                                result.getInt("spot_number"),
                                SpotType.valueOf(result.getString("spot_type")));
                    }
                }

                long charge = feeCalculator.calculateCents(entryTime, exitTime, rate);
                try (PreparedStatement finishSession = connection.prepareStatement(
                        "UPDATE parking_sessions SET exit_time_ms = ?, charged_cents = ? "
                                + "WHERE id = ? AND exit_time_ms IS NULL");
                     PreparedStatement releaseSpot = connection.prepareStatement(
                             "UPDATE parking_spots SET occupied = FALSE WHERE id = ? AND occupied = TRUE");
                     PreparedStatement removeActive = connection.prepareStatement(
                             "DELETE FROM active_vehicles WHERE license_plate = ?")) {
                    finishSession.setLong(1, exitTime.toEpochMilli());
                    finishSession.setLong(2, charge);
                    finishSession.setLong(3, sessionId);
                    if (finishSession.executeUpdate() != 1) {
                        throw new ParkingException("Parking session was already checked out");
                    }
                    releaseSpot.setLong(1, spot.spotId());
                    if (releaseSpot.executeUpdate() != 1) {
                        throw new ParkingException("Parking spot state is inconsistent for vehicle " + plate);
                    }
                    removeActive.setString(1, plate);
                    if (removeActive.executeUpdate() != 1) {
                        throw new ParkingException("Active vehicle state changed; please retry checkout");
                    }
                }
                connection.commit();
                LOGGER.log(System.Logger.Level.INFO, "Vehicle {0} checked out; charged {1} cents", plate, charge);
                return new ParkingReceipt(sessionId, plate, vehicleType, spot, entryTime, exitTime,
                        feeCalculator.durationMinutes(entryTime, exitTime), charge);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new ParkingException("Database error while checking out vehicle " + plate, exception);
        }
    }

    public List<Availability> getAvailability() {
        String query = "SELECT floor_number, spot_type, COUNT(*) AS available_count "
                + "FROM parking_spots WHERE occupied = FALSE "
                + "GROUP BY floor_number, spot_type ORDER BY floor_number, spot_type";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(query);
             ResultSet result = statement.executeQuery()) {
            List<Availability> availability = new ArrayList<>();
            while (result.next()) {
                availability.add(new Availability(
                        result.getInt("floor_number"),
                        SpotType.valueOf(result.getString("spot_type")),
                        result.getLong("available_count")));
            }
            return availability;
        } catch (SQLException exception) {
            throw new ParkingException("Could not read parking availability", exception);
        }
    }

    public List<ActiveParking> getActiveVehicles(int limit, int offset) {
        if (limit <= 0 || limit > 500 || offset < 0) {
            throw new IllegalArgumentException("Page size must be between 1 and 500 and offset cannot be negative");
        }
        String query = "SELECT s.id, s.license_plate, s.vehicle_type, s.entry_time_ms, "
                + "p.id AS spot_id, p.floor_number, p.spot_number, p.spot_type "
                + "FROM active_vehicles a JOIN parking_sessions s ON s.id = a.session_id "
                + "JOIN parking_spots p ON p.id = s.spot_id "
                + "ORDER BY s.entry_time_ms, s.id LIMIT ? OFFSET ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, limit);
            statement.setInt(2, offset);
            try (ResultSet result = statement.executeQuery()) {
                List<ActiveParking> activeVehicles = new ArrayList<>();
                while (result.next()) {
                    activeVehicles.add(new ActiveParking(
                            result.getLong("id"),
                            result.getString("license_plate"),
                            VehicleType.valueOf(result.getString("vehicle_type")),
                            new SpotAssignment(
                                    result.getLong("spot_id"),
                                    result.getInt("floor_number"),
                                    result.getInt("spot_number"),
                                    SpotType.valueOf(result.getString("spot_type"))),
                            Instant.ofEpochMilli(result.getLong("entry_time_ms"))));
                }
                return activeVehicles;
            }
        } catch (SQLException exception) {
            throw new ParkingException("Could not read active parking sessions", exception);
        }
    }

    private SpotAssignment allocateSpot(Connection connection, VehicleType vehicleType) throws SQLException {
        List<Integer> floors = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT floor_number FROM floors ORDER BY floor_number");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                floors.add(result.getInt(1));
            }
        }

        List<SpotType> compatibleTypes = vehicleType.compatibleSpotTypes();
        StringJoiner placeholders = new StringJoiner(", ");
        compatibleTypes.forEach(ignored -> placeholders.add("?"));
        String query = "SELECT id, floor_number, spot_number, spot_type FROM parking_spots "
                + "WHERE floor_number = ? AND occupied = FALSE AND spot_type IN (" + placeholders + ") "
                + "ORDER BY CASE spot_type WHEN 'MOTORCYCLE' THEN 0 WHEN 'COMPACT' THEN 1 "
                + "WHEN 'LARGE' THEN 2 ELSE 3 END, spot_number LIMIT 1";

        for (int floor : floors) {
            try (PreparedStatement floorLock = connection.prepareStatement(
                    "SELECT floor_number FROM floors WHERE floor_number = ? FOR UPDATE")) {
                floorLock.setInt(1, floor);
                try (ResultSet ignored = floorLock.executeQuery()) {
                    if (!ignored.next()) {
                        continue;
                    }
                }
            }
            try (PreparedStatement findSpot = connection.prepareStatement(query)) {
                findSpot.setInt(1, floor);
                for (int index = 0; index < compatibleTypes.size(); index++) {
                    findSpot.setString(index + 2, compatibleTypes.get(index).name());
                }
                try (ResultSet result = findSpot.executeQuery()) {
                    if (result.next()) {
                        return new SpotAssignment(
                                result.getLong("id"),
                                result.getInt("floor_number"),
                                result.getInt("spot_number"),
                                SpotType.valueOf(result.getString("spot_type")));
                    }
                }
            }
        }
        throw new NoAvailableSpotException("No compatible parking spot is currently available");
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
