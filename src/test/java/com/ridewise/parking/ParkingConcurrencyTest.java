package com.ridewise.parking;

import com.ridewise.parking.db.Database;
import com.ridewise.parking.exception.NoAvailableSpotException;
import com.ridewise.parking.model.ParkingModels.ActiveParking;
import com.ridewise.parking.model.VehicleType;
import com.ridewise.parking.repository.ParkingRepository;
import com.ridewise.parking.service.FeeCalculator;
import com.ridewise.parking.service.ParkingService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParkingConcurrencyTest {
    @Test
    void concurrentCheckInsNeverAllocateOneSpotTwiceOrExceedCapacity() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("database.url", "jdbc:h2:mem:concurrency;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000");
        properties.setProperty("database.user", "sa");
        properties.setProperty("database.pool-size", "16");
        try (Database database = new Database(properties)) {
            database.initialize(1, Map.of(
                    "MOTORCYCLE", 0,
                    "COMPACT", 6,
                    "LARGE", 0,
                    "BUS", 0));
            ParkingService service = new ParkingService(
                    new ParkingRepository(database, new FeeCalculator()),
                    Map.of(
                            VehicleType.MOTORCYCLE, 100L,
                            VehicleType.CAR, 300L,
                            VehicleType.BUS, 800L),
                    Clock.systemUTC());

            int attempts = 20;
            ExecutorService executor = Executors.newFixedThreadPool(attempts);
            CountDownLatch ready = new CountDownLatch(attempts);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger parkedCount = new AtomicInteger();
            List<Future<?>> results = new ArrayList<>();
            try {
                for (int i = 0; i < attempts; i++) {
                    int vehicleId = i;
                    results.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        try {
                            service.checkIn("TEST-" + vehicleId, VehicleType.CAR);
                            parkedCount.incrementAndGet();
                        } catch (NoAvailableSpotException expected) {
                            // Capacity rejection is the expected outcome for excess arrivals.
                        }
                        return null;
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                for (Future<?> result : results) {
                    result.get(20, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }

            List<ActiveParking> active = service.getActiveVehicles(100, 0);
            assertEquals(6, parkedCount.get());
            assertEquals(6, active.size());
            assertEquals(6, active.stream().map(item -> item.spot().spotId()).distinct().count());
        }
    }
}
