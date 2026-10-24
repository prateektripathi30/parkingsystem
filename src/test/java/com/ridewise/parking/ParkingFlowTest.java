package com.ridewise.parking;

import com.ridewise.parking.db.Database;
import com.ridewise.parking.exception.AlreadyParkedException;
import com.ridewise.parking.exception.NoAvailableSpotException;
import com.ridewise.parking.exception.NotParkedException;
import com.ridewise.parking.model.ParkingModels.ParkingReceipt;
import com.ridewise.parking.model.VehicleType;
import com.ridewise.parking.repository.ParkingRepository;
import com.ridewise.parking.service.FeeCalculator;
import com.ridewise.parking.service.ParkingService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParkingFlowTest {
    @Test
    void checkInDuplicateFullLotCheckoutAndSpotReuse() {
        Properties properties = new Properties();
        properties.setProperty("database.url", "jdbc:h2:mem:parking-flow;DB_CLOSE_DELAY=-1");
        properties.setProperty("database.user", "sa");
        try (Database database = new Database(properties)) {
            database.initialize(1, Map.of(
                    "MOTORCYCLE", 0,
                    "COMPACT", 1,
                    "LARGE", 0,
                    "BUS", 0));
            MutableClock clock = new MutableClock(Instant.parse("2026-03-01T10:00:00Z"));
            ParkingService service = new ParkingService(
                    new ParkingRepository(database, new FeeCalculator()),
                    Map.of(
                            VehicleType.MOTORCYCLE, 100L,
                            VehicleType.CAR, 300L,
                            VehicleType.BUS, 800L),
                    clock);

            service.checkIn("abc-123", VehicleType.CAR);
            assertThrows(AlreadyParkedException.class, () -> service.checkIn("ABC-123", VehicleType.CAR));
            assertThrows(NoAvailableSpotException.class, () -> service.checkIn("SECOND", VehicleType.CAR));

            clock.advanceSeconds(3601);
            ParkingReceipt receipt = service.checkOut("abc-123");
            assertEquals(600, receipt.chargedCents());
            assertThrows(NotParkedException.class, () -> service.checkOut("ABC-123"));

            service.checkIn("SECOND", VehicleType.CAR);
            assertEquals(1, service.getActiveVehicles(10, 0).size());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
