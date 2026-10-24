package com.ridewise.parking.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

public final class Database implements AutoCloseable {
    private final HikariDataSource dataSource;

    public Database(Properties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(requiredProperty(properties, "database.url"));
        config.setUsername(properties.getProperty("database.user", "sa"));
        config.setPassword(properties.getProperty("database.password", ""));
        config.setMaximumPoolSize(integerProperty(properties, "database.pool-size", 16));
        config.setMinimumIdle(Math.min(2, config.getMaximumPoolSize()));
        config.setPoolName("parking-system-pool");
        config.setConnectionTimeout(10_000);
        this.dataSource = new HikariDataSource(config);
    }

    public static Properties loadDefaultProperties() {
        Properties properties = new Properties();
        try (InputStream input = Database.class.getResourceAsStream("/application.properties")) {
            if (input == null) {
                throw new IllegalStateException("application.properties is missing from the classpath");
            }
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load application.properties", exception);
        }
        return properties;
    }

    public static int integerProperty(Properties properties, String key, int defaultValue) {
        String value = System.getProperty(key, properties.getProperty(key));
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(key + " must be greater than zero");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be a positive integer", exception);
        }
    }

    private static String requiredProperty(Properties properties, String key) {
        String value = System.getProperty(key, properties.getProperty(key));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required property: " + key);
        }
        return value;
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void initialize(int floorCount, Map<String, Integer> spotCounts) {
        if (floorCount <= 0 || spotCounts.values().stream().anyMatch(count -> count < 0)) {
            throw new IllegalArgumentException("Floor count must be positive and spot counts cannot be negative");
        }
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            String schema = readSchema();
            for (String sql : schema.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
            seedLayout(connection, floorCount, spotCounts);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize the parking database", exception);
        }
    }

    private static String readSchema() {
        try (InputStream input = Database.class.getResourceAsStream("/schema.sql")) {
            if (input == null) {
                throw new IllegalStateException("schema.sql is missing from the classpath");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read schema.sql", exception);
        }
    }

    private static void seedLayout(Connection connection, int floorCount, Map<String, Integer> spotCounts)
            throws SQLException {
        String addFloor = "INSERT INTO floors(floor_number) SELECT ? WHERE NOT EXISTS "
                + "(SELECT 1 FROM floors WHERE floor_number = ?)";
        String addSpot = "INSERT INTO parking_spots(floor_number, spot_number, spot_type, occupied) "
                + "SELECT ?, ?, ?, FALSE WHERE NOT EXISTS "
                + "(SELECT 1 FROM parking_spots WHERE floor_number = ? AND spot_number = ?)";
        try (PreparedStatement floorStatement = connection.prepareStatement(addFloor);
             PreparedStatement spotStatement = connection.prepareStatement(addSpot)) {
            for (int floor = 1; floor <= floorCount; floor++) {
                floorStatement.setInt(1, floor);
                floorStatement.setInt(2, floor);
                floorStatement.executeUpdate();

                int spotNumber = 1;
                for (Map.Entry<String, Integer> type : spotCounts.entrySet()) {
                    for (int count = 0; count < type.getValue(); count++) {
                        spotStatement.setInt(1, floor);
                        spotStatement.setInt(2, spotNumber);
                        spotStatement.setString(3, type.getKey());
                        spotStatement.setInt(4, floor);
                        spotStatement.setInt(5, spotNumber);
                        spotStatement.executeUpdate();
                        spotNumber++;
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
