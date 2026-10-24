# Smart Parking System

A Java 17+ reference implementation for a multi-floor parking lot. It supports vehicle check-in/check-out, size-aware spot allocation, availability reporting, durable parking sessions, configurable fees, and concurrent arrivals/departures. The implementation follows the supplied brief and uses embedded H2 so it can be run locally without a separate database server.

## Requirements covered

| Requirement | Implementation |
| --- | --- |
| Automatic allocation by vehicle size | `ParkingRepository.allocateSpot` chooses the smallest compatible spot type, then the lowest-numbered spot on the first available floor. |
| Check-in and check-out | Each visit is a durable `parking_sessions` row with UTC epoch-millisecond entry and exit times. Active visits are indexed separately. |
| Duration- and vehicle-based pricing | The hourly price is snapshotted at check-in. Each started hour is charged; a stay of exactly zero duration is free. Values are stored as integer cents. |
| Live availability | Availability is calculated from committed spot state in the database; a successful entry/exit updates it in the same transaction as the session. |
| Data model | `schema.sql` defines floors, spots, parking sessions, and active vehicles with keys, checks, and indexes. |
| Concurrency | Check-in locks floor rows in a consistent order. Arrivals on the same floor serialize, while independent floors can allocate concurrently. Check-in and check-out update the session, active-vehicle record, and spot atomically. |

## Design and behavior

**Vehicle fit policy:** motorcycles may use any spot (motorcycle, compact, large, or bus) and receive the smallest available one; cars use compact or large spots; buses require a bus spot. Cars do not consume bus spots. A bus uses one dedicated bus-sized spot (the model does not require a multi-spot contiguous bus bay).

**Transaction safety:** all writes run in JDBC transactions. A unique primary key on `active_vehicles.license_plate` prevents a plate from being checked in twice, including simultaneous duplicate requests. Floor-row locks prevent two arrivals from selecting the same spot. Check-out locks the active-vehicle row and atomically records the fee, releases the spot, and removes the active mapping. Failed operations roll back rather than leaving partially updated state.

**Scale considerations:** spot lookup is indexed by floor, occupancy, type, and spot number; plate/session lookups use indexes; active-session display is fetched in pages of 100; session history is retained without loading it for ordinary allocation. HikariCP pools JDBC connections. Work is bounded to a floor's compatible candidates rather than scanning the full transaction history. H2 is suitable for a single-node/local deployment and development; for a multi-node, high-write production deployment, use PostgreSQL (the repository/schema would need a database-specific migration and locking adaptation).

### Data model

- `floors(floor_number)`: floor identifiers used as per-floor allocation locks.
- `parking_spots(id, floor_number, spot_number, spot_type, occupied)`: uniquely numbered capacity on each floor.
- `parking_sessions(id, license_plate, vehicle_type, spot_id, entry_time_ms, exit_time_ms, hourly_rate_cents, charged_cents)`: immutable visit history plus the check-out result.
- `active_vehicles(license_plate, session_id)`: one current session per normalized plate; removing this row does not remove visit history.

Amounts use cents to avoid floating-point rounding. Timestamps are UTC epoch milliseconds, avoiding local-time/DST ambiguity. The configured hourly rate is stored on the visit so a later rate change does not alter an in-progress session's fee.

## Project structure

```text
src/main/java/com/ridewise/parking/
  ParkingApplication.java          Console entry point and input handling
  db/Database.java                 HikariCP setup, schema initialization, initial lot layout
  exception/                       Domain errors for full lot, duplicate check-in, and invalid checkout
  model/                            Vehicle/spot types and immutable response records
  repository/ParkingRepository.java JDBC queries and transactional allocation/check-out
  service/FeeCalculator.java        Integer-cent fee and duration calculations
  service/ParkingService.java       Validation and application-level operations
src/main/resources/
  application.properties           Database, lot capacity, and fee defaults
  schema.sql                        Database schema and indexes
src/test/java/                      Fee boundary and simultaneous-arrival tests
```

## Run

Prerequisites: JDK 17 or newer and Maven 3.9+.

From PowerShell:

```powershell
cd C:\Users\prate\IdeaProjects\parkingsystem
mvn test
mvn package
mvn exec:java -Dexec.mainClass="com.ridewise.parking.ParkingApplication"
```

The Maven Exec plugin is configured in the project; `mvn package` creates a regular JAR, while `mvn exec:java` runs the application with its dependencies.

On first launch, the system creates `.\data\parking.mv.db` and seeds three floors with 4 motorcycle, 10 compact, 8 large, and 2 bus spots per floor. Initialization is idempotent: existing occupied spaces and transaction history are not reset. The console offers check-in, check-out, availability, active-vehicle listing, and exit.

### Configuration

Defaults are in `src/main/resources/application.properties`. Override any property at launch with a Java system property, for example:

```powershell
mvn exec:java "-Dexec.mainClass=com.ridewise.parking.ParkingApplication" "-Dfee.car-hourly-cents=450" "-Dlot.floor-count=4"
```

Supported settings include `database.url`, `database.user`, `database.password`, `database.pool-size`, `lot.floor-count`, the four `lot.*-spots-per-floor` capacities, and hourly rates in cents: `fee.motorcycle-hourly-cents`, `fee.car-hourly-cents`, and `fee.bus-hourly-cents`. Rates may be zero. Floor count must be positive and each spot count must be non-negative. Do not change the seeded lot dimensions after the database has already been initialized unless you intend to add capacity; initialization adds missing configured spots but deliberately does not delete existing ones.

## Validation and failure behavior

- Empty, overlong, or unsupported plate characters and unknown vehicle types are rejected before database writes.
- A vehicle already checked in, an unavailable compatible spot, and an unknown/not-parked vehicle at exit are explicit errors.
- A failed database write rolls back all related state. SQL and startup failures are logged and reported; they are not represented as a successful operation.
- Fee calculation rejects negative durations/rates and detects integer overflow. Checkout cannot precede check-in.
- Database row locks and unique constraints protect concurrent check-in/check-out operations. JDBC lock timeout is configured to 15 seconds and can be tuned in the database URL.
- Application logs use the JDK system logger; user-facing validation failures are also shown in the console.

Run `mvn test` to execute fee-boundary tests, check-in/checkout failure-flow tests, and a concurrent check-in test that verifies capacity is never exceeded and no spot is assigned twice.

## Operational boundaries

This is a backend-domain/reference implementation with a console adapter, not an HTTP service. Authentication, payment processing, hardware gates/sensors, plate recognition, reservations, pricing calendars/taxes, audit retention policy, and administrative spot-maintenance endpoints are outside the supplied brief. H2 file persistence is single-node; use a server database and a production migration strategy before deploying a large multi-process facility.
