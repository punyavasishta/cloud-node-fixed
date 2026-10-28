# Cloud Node — Flight Booking

Same **Cloud Node** theme (green / cream / gold). Booking works like a full multi-step flow, backed by MySQL via `FlightServer`.

## Booking procedure (like the rich demo flow)

1. **Search** — From / To / optional date (only flights with open seats).
2. **Passenger** — Choose a registered passenger (add under **Passengers** if needed).
3. **Seat map** — Cabin grid; taken seats disabled (`GET /api/seats`); legroom rows highlighted.
4. **Meals** — Optional complimentary meal choice (saved as special request).
5. **Contact & payment** — Name / email / phone + UPI / Card / NetBanking / Wallet (demo; no real charge).
6. **Confirm** — Writes `Bookings`. If you ran `flight_booking_extensions.sql`, also writes `BookingDetails` + `Payments`.
7. **Done** — Boarding-pass style card with **PNR** (e.g. `CN000021`).

The **Database** section adds a live Table Explorer for all tables in the connected schema, with keys, column types, row counts, and searchable previews. The SQL Query Studio runs read-only flight queries and includes JOIN, aggregation, subquery, and seat-availability examples.

## Setup

1. MySQL — for a fresh install, create the schema and sample data:

```bash
mysql -u root -p < flight_booking.sql
mysql -u root -p < flight_booking_extensions.sql   # recommended (Payments, BookingDetails, daily flights)
```

`flight_booking.sql` creates the database from scratch and contains 15 sample flights. The extensions script generates the additional daily flights (2,249 total with the included seed data). If the database already exists with those 15 flights, do not rerun `flight_booking.sql` because it drops and recreates the database. Instead, run this from the extracted project folder:

```bash
./setup_database.sh
```

It prompts for the MySQL password, runs `flight_booking_extensions.sql`, and prints the flight count. It connects to localhost as `root` by default; set `DB_HOST`, `DB_PORT`, and `DB_USER` first if your MySQL connection differs.

2. Password — `./run.sh` asks for your MySQL password if `DB_PASS` is not set. Or set it in the terminal before starting:

```bash
export DB_PASS='your_password'
```

3. Run from the project folder:

```bash
chmod +x run.sh
./run.sh
```

In VS Code, open the project folder, open `FlightServer.java`, then click **Run Code** (the Code Runner triangle). It now starts `run.sh`, which compiles the server and prompts for the MySQL password. When the terminal says it connected to MySQL, open **http://localhost:8080** in your browser. If MySQL is stopped or the password is wrong, the web server cannot start.

## API

| Endpoint | Purpose |
|----------|---------|
| `GET /api/flights` | All flights + availability |
| `GET /api/seats?flightId=N` | Taken seat numbers for the map |
| `GET /api/passengers` | Registered passengers |
| `POST /api/passengers/add` | Add passenger |
| `POST /api/book` | Book with passenger, flight, seat + optional contact / payment / specialRequests → bookingId + pnr |
| `POST /api/cancel` | Cancel booking |
| `GET /api/bookings` | Full booking list |
| `GET /api/unbooked-flights` | Flights with no bookings |
| `GET /api/top-flights` | Top 3 by bookings |
| `GET /api/airline-counts` | Flights per airline |
| `GET /api/db/overview` | Tables in the connected schema with row counts |
| `GET /api/db/table?name=Flights` | Column/key metadata and first 200 table rows |
| `GET /api/db/presets` | Flight-focused showcase SQL queries |
| `POST /api/db/query` | Run one read-only `SELECT`, `SHOW`, `DESCRIBE`, or `EXPLAIN` query (up to 1,000 rows) |

## Files

- index.html — frontend (Cloud Node theme + rich booking)
- FlightServer.java — HTTP + JDBC API
- mysql-connector-j-26.7.0.jar — MySQL driver
- flight_booking.sql — base schema + seed
- flight_booking_extensions.sql — schedules, daily instances, Payments, BookingDetails
- run.sh — compile & start
