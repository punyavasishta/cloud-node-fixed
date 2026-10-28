import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * Cloud Node Flight Booking - enhanced server
 * Supports rich booking flow: seats, contact, payment records.
 */
public class FlightServer {

    static final String URL  = env("DB_URL",  "jdbc:mysql://localhost:3306/flight_booking");
    static final String USER = env("DB_USER", "root");
    static final String PASS = env("DB_PASS", "Apple23@_");

    static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null ? d : v;
    }

    static Connection db() throws SQLException {
        try { Class.forName("com.mysql.cj.jdbc.Driver"); }
        catch (ClassNotFoundException e) {
            throw new SQLException("MySQL driver not found. Run with: java -cp \".:mysql-connector-j-26.7.0.jar\" FlightServer");
        }
        return DriverManager.getConnection(URL, USER, PASS);
    }

    static boolean tableExists(Connection c, String name) {
        try (ResultSet rs = c.getMetaData().getTables(null, null, name, null)) {
            return rs.next();
        } catch (SQLException e) { return false; }
    }

    public static void main(String[] args) throws Exception {
        try (Connection c = db()) {
            System.out.println("Connected to MySQL.");
        } catch (SQLException e) {
            System.out.println("Could not connect to MySQL: " + e.getMessage());
            System.out.println("Edit PASS in FlightServer.java or set DB_PASS env.");
            return;
        }

        int port = 8080;
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); } catch (Exception ignored) {}
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        // Frontend
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                Path html = Path.of("index.html");
                if (!Files.exists(html)) {
                    send(ex, 404, "text/plain", "index.html not found");
                    return;
                }
                send(ex, 200, "text/html", Files.readString(html));
            } else {
                send(ex, 404, "text/plain", "Not found");
            }
        });

        // ===== READ APIs =====

        server.createContext("/api/flights", ex -> {
            cors(ex);
            if (opt(ex)) return;
            send(ex, 200, "application/json", query("""
                SELECT f.flight_id, f.flight_number, a.airline_name, f.departure_city, f.arrival_city,
                       DATE_FORMAT(f.departure_time, '%d-%b-%Y %H:%i') AS departure_time,
                       DATE_FORMAT(f.arrival_time, '%d-%b-%Y %H:%i') AS arrival_time,
                       f.capacity, f.base_price,
                       (SELECT COUNT(*) FROM Bookings b WHERE b.flight_id=f.flight_id AND b.status!='Cancelled') AS booked
                FROM Flights f JOIN Airlines a ON f.airline_id=a.airline_id
                ORDER BY f.departure_time
                """, rs -> arr(rs, r -> obj(
                    "flightId", r.getInt(1),
                    "flightNumber", r.getString(2),
                    "airlineName", r.getString(3),
                    "departureCity", r.getString(4),
                    "arrivalCity", r.getString(5),
                    "departureTime", r.getString(6),
                    "arrivalTime", r.getString(7),
                    "capacity", r.getInt(8),
                    "basePrice", r.getDouble(9),
                    "booked", r.getInt(10),
                    "available", r.getInt(8) - r.getInt(10)
                ))));
        });

        // Database explorer: expose every table that exists in the connected schema.
        server.createContext("/api/db/overview", ex -> {
            cors(ex);
            if (opt(ex)) return;
            try (Connection c = db()) {
                List<Map<String,Object>> tables = new ArrayList<>();
                for (String name : databaseTables(c)) {
                    long count;
                    try (Statement st = c.createStatement();
                         ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + quoteIdentifier(name))) {
                        rs.next();
                        count = rs.getLong(1);
                    }
                    Map<String,Object> item = new LinkedHashMap<>();
                    item.put("tableName", name);
                    item.put("rowCount", count);
                    tables.add(item);
                }
                send(ex, 200, "application/json", json(Map.of("tables", tables, "database", c.getCatalog())));
            } catch (Exception e) {
                send(ex, 500, "application/json", json(Map.of("error", safeMessage(e))));
            }
        });

        server.createContext("/api/db/table", ex -> {
            cors(ex);
            if (opt(ex)) return;
            try (Connection c = db()) {
                String name = queryParam(ex.getRequestURI().getRawQuery(), "name");
                if (name.isEmpty() || !databaseTables(c).contains(name)) {
                    send(ex, 404, "application/json", json(Map.of("error", "Table not found")));
                    return;
                }
                String safeName = quoteIdentifier(name);
                List<Map<String,Object>> columns = new ArrayList<>();
                Set<String> primaryKeys = new HashSet<>();
                try (ResultSet pk = c.getMetaData().getPrimaryKeys(c.getCatalog(), null, name)) {
                    while (pk.next()) primaryKeys.add(pk.getString("COLUMN_NAME"));
                }
                Map<String,String> foreignKeys = new HashMap<>();
                try (ResultSet fk = c.getMetaData().getImportedKeys(c.getCatalog(), null, name)) {
                    while (fk.next()) foreignKeys.put(fk.getString("FKCOLUMN_NAME"),
                        fk.getString("PKTABLE_NAME") + "." + fk.getString("PKCOLUMN_NAME"));
                }
                try (ResultSet cols = c.getMetaData().getColumns(c.getCatalog(), null, name, null)) {
                    while (cols.next()) {
                        Map<String,Object> col = new LinkedHashMap<>();
                        String columnName = cols.getString("COLUMN_NAME");
                        col.put("name", columnName);
                        col.put("type", cols.getString("TYPE_NAME"));
                        col.put("nullable", "YES".equalsIgnoreCase(cols.getString("IS_NULLABLE")));
                        col.put("primaryKey", primaryKeys.contains(columnName));
                        col.put("foreignKey", foreignKeys.get(columnName));
                        columns.add(col);
                    }
                }
                long totalRows;
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + safeName)) {
                    rs.next(); totalRows = rs.getLong(1);
                }
                List<Map<String,Object>> rows = new ArrayList<>();
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + safeName + " LIMIT 200")) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                        rows.add(row);
                    }
                }
                Map<String,Object> result = new LinkedHashMap<>();
                result.put("tableName", name); result.put("columns", columns);
                result.put("rows", rows); result.put("totalRows", totalRows); result.put("previewLimit", 200);
                send(ex, 200, "application/json", json(result));
            } catch (Exception e) {
                send(ex, 500, "application/json", json(Map.of("error", safeMessage(e))));
            }
        });

        server.createContext("/api/db/presets", ex -> {
            cors(ex);
            if (opt(ex)) return;
            send(ex, 200, "application/json", json(flightQueryPresets()));
        });

        server.createContext("/api/db/query", ex -> {
            cors(ex);
            if (opt(ex)) return;
            try {
                Map<String,String> body = parseBody(ex);
                String sql = req(body, "sql").trim();
                String firstWord = sql.replaceFirst("(?s)^\\s*(?:--[^\\n]*\\n\\s*|/\\*.*?\\*/\\s*)*", "")
                    .split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
                if (!Set.of("SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN").contains(firstWord)) {
                    throw new IllegalArgumentException("SQL Studio runs read-only SELECT, SHOW, DESCRIBE, and EXPLAIN statements.");
                }
                String singleStatement = sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
                if (singleStatement.contains(";")) {
                    throw new IllegalArgumentException("Run one SQL statement at a time.");
                }
                long started = System.nanoTime();
                Map<String,Object> result = runReadOnlySql(sql);
                result.put("executionTimeMs", (System.nanoTime() - started) / 1_000_000.0);
                send(ex, 200, "application/json", json(result));
            } catch (Exception e) {
                send(ex, 400, "application/json", json(Map.of("error", safeMessage(e))));
            }
        });

        // Taken seats for a flight (for seat map)
        server.createContext("/api/seats", ex -> {
            cors(ex);
            if (opt(ex)) return;
            try {
                String q = ex.getRequestURI().getQuery();
                int flightId = -1;
                if (q != null) {
                    for (String part : q.split("&")) {
                        String[] kv = part.split("=", 2);
                        if (kv.length == 2 && "flightId".equals(kv[0])) {
                            flightId = Integer.parseInt(kv[1]);
                        }
                    }
                }
                if (flightId < 1) {
                    send(ex, 400, "application/json", "{\"error\":\"Missing flightId\"}");
                    return;
                }
                final int fid = flightId;
                send(ex, 200, "application/json", query(
                    "SELECT seat_number FROM Bookings WHERE flight_id=" + fid + " AND status!='Cancelled' AND seat_number IS NOT NULL",
                    rs -> {
                        StringBuilder sb = new StringBuilder("[");
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) sb.append(",");
                            first = false;
                            sb.append("\"").append(esc(rs.getString(1))).append("\"");
                        }
                        return sb.append("]").toString();
                    }));
            } catch (Exception e) {
                send(ex, 400, "application/json", "{\"error\":\"" + esc(e.getMessage()) + "\"}");
            }
        });

        server.createContext("/api/airline-counts", ex -> {
            cors(ex);
            if (opt(ex)) return;
            send(ex, 200, "application/json", query("""
                SELECT a.airline_name, a.iata_code, COUNT(f.flight_id) AS total
                FROM Airlines a LEFT JOIN Flights f ON a.airline_id=f.airline_id
                GROUP BY a.airline_id ORDER BY total DESC
                """, rs -> arr(rs, r -> obj(
                    "airlineName", r.getString(1),
                    "iataCode", r.getString(2),
                    "totalFlights", r.getInt(3)
                ))));
        });

        server.createContext("/api/bookings", ex -> {
            cors(ex);
            if (opt(ex)) return;
            send(ex, 200, "application/json", query("""
                SELECT b.booking_id, CONCAT(p.first_name,' ',p.last_name), f.flight_number,
                       a.airline_name, CONCAT(f.departure_city,' -> ',f.arrival_city),
                       DATE_FORMAT(f.departure_time,'%d-%b-%Y %H:%i'),
                       b.seat_number, b.status, b.total_amount,
                       DATE_FORMAT(b.booking_date,'%d-%b-%Y %H:%i')
                FROM Bookings b
                JOIN Passengers p ON b.passenger_id=p.passenger_id
                JOIN Flights f ON b.flight_id=f.flight_id
                JOIN Airlines a ON f.airline_id=a.airline_id
                ORDER BY b.booking_date DESC
                """, rs -> arr(rs, r -> obj(
                    "bookingId", r.getInt(1),
                    "passengerName", r.getString(2),
                    "flightNumber", r.getString(3),
                    "airlineName", r.getString(4),
                    "route", r.getString(5),
                    "departureTime", r.getString(6),
                    "seatNumber", r.getString(7),
                    "status", r.getString(8),
                    "totalAmount", r.getDouble(9),
                    "bookingDate", r.getString(10)
                ))));
        });

        server.createContext("/api/unbooked-flights", ex -> {
            cors(ex);
            if (opt(ex)) return;
            send(ex, 200, "application/json", query("""
                SELECT f.flight_number, a.airline_name, f.departure_city, f.arrival_city,
                       DATE_FORMAT(f.departure_time,'%d-%b-%Y %H:%i')
                FROM Flights f JOIN Airlines a ON f.airline_id=a.airline_id
                LEFT JOIN Bookings b ON f.flight_id=b.flight_id
                WHERE b.booking_id IS NULL ORDER BY f.departure_time
                """, rs -> arr(rs, r -> obj(
                    "flightNumber", r.getString(1),
                    "airlineName", r.getString(2),
                    "departureCity", r.getString(3),
                    "arrivalCity", r.getString(4),
                    "departureTime", r.getString(5)
                ))));
        });

        server.createContext("/api/top-flights", ex -> {
            cors(ex);
            if (opt(ex)) return;
            send(ex, 200, "application/json", query("""
                SELECT f.flight_number, a.airline_name,
                       CONCAT(f.departure_city,' -> ',f.arrival_city),
                       COUNT(b.booking_id)
                FROM Flights f
                JOIN Airlines a ON f.airline_id=a.airline_id
                JOIN Bookings b ON f.flight_id=b.flight_id
                GROUP BY f.flight_id ORDER BY COUNT(b.booking_id) DESC LIMIT 3
                """, rs -> arr(rs, r -> obj(
                    "flightNumber", r.getString(1),
                    "airlineName", r.getString(2),
                    "route", r.getString(3),
                    "numberOfBookings", r.getInt(4)
                ))));
        });

        server.createContext("/api/passengers", ex -> {
            cors(ex);
            if (opt(ex)) return;
            send(ex, 200, "application/json", query(
                "SELECT passenger_id, first_name, last_name, email, phone FROM Passengers ORDER BY first_name",
                rs -> arr(rs, r -> obj(
                    "passengerId", r.getInt(1),
                    "firstName", r.getString(2),
                    "lastName", r.getString(3),
                    "email", r.getString(4),
                    "phone", r.getString(5)
                ))));
        });

        // ===== WRITE APIs =====

        // Book a flight (returns bookingId; optional contact + payment)
        server.createContext("/api/book", ex -> {
            cors(ex);
            if (opt(ex)) return;
            try {
                Map<String,String> p = parseBody(ex);
                int passengerId = Integer.parseInt(req(p, "passengerId"));
                int flightId = Integer.parseInt(req(p, "flightId"));
                String seat = req(p, "seatNumber").toUpperCase();
                String contactName = p.getOrDefault("contactName", "").trim();
                String contactEmail = p.getOrDefault("contactEmail", "").trim();
                String contactPhone = p.getOrDefault("contactPhone", "").trim();
                String specialRequests = p.getOrDefault("specialRequests", "").trim();
                String payMethod = p.getOrDefault("paymentMethod", "UPI").trim();
                if (!List.of("Card","UPI","NetBanking","Wallet","Cash").contains(payMethod)) {
                    payMethod = "UPI";
                }

                int bookingId;
                double price;
                String pnr;

                try (Connection c = db()) {
                    c.setAutoCommit(false);
                    try {
                        int capacity, booked;
                        try (PreparedStatement ps = c.prepareStatement(
                                "SELECT capacity, base_price, (SELECT COUNT(*) FROM Bookings b WHERE b.flight_id=? AND b.status!='Cancelled') FROM Flights WHERE flight_id=?")) {
                            ps.setInt(1, flightId);
                            ps.setInt(2, flightId);
                            ResultSet rs = ps.executeQuery();
                            if (!rs.next()) throw new IllegalArgumentException("Flight not found");
                            capacity = rs.getInt(1);
                            price = rs.getDouble(2);
                            booked = rs.getInt(3);
                        }
                        if (booked >= capacity) throw new IllegalArgumentException("Flight is full");

                        try (PreparedStatement ps = c.prepareStatement(
                                "SELECT COUNT(*) FROM Bookings WHERE flight_id=? AND seat_number=? AND status!='Cancelled'")) {
                            ps.setInt(1, flightId);
                            ps.setString(2, seat);
                            ResultSet rs = ps.executeQuery();
                            rs.next();
                            if (rs.getInt(1) > 0) throw new IllegalArgumentException("Seat " + seat + " already taken");
                        }

                        try (PreparedStatement ps = c.prepareStatement(
                                "INSERT INTO Bookings (passenger_id, flight_id, seat_number, status, total_amount) VALUES (?,?,?,'Confirmed',?)",
                                Statement.RETURN_GENERATED_KEYS)) {
                            ps.setInt(1, passengerId);
                            ps.setInt(2, flightId);
                            ps.setString(3, seat);
                            ps.setDouble(4, price);
                            ps.executeUpdate();
                            ResultSet keys = ps.getGeneratedKeys();
                            keys.next();
                            bookingId = keys.getInt(1);
                        }

                        pnr = "CN" + String.format("%06d", bookingId);

                        // Optional BookingDetails (if extensions applied)
                        if (tableExists(c, "BookingDetails") && !contactName.isEmpty()) {
                            try (PreparedStatement ps = c.prepareStatement(
                                    "INSERT INTO BookingDetails (booking_id, contact_name, contact_email, contact_phone, special_requests) VALUES (?,?,?,?,?)")) {
                                ps.setInt(1, bookingId);
                                ps.setString(2, contactName);
                                ps.setString(3, contactEmail.isEmpty() ? "n/a@cloudnode.local" : contactEmail);
                                ps.setString(4, contactPhone.isEmpty() ? null : contactPhone);
                                ps.setString(5, specialRequests.isEmpty() ? null : specialRequests);
                                ps.executeUpdate();
                            }
                        }

                        // Optional Payments (if extensions applied)
                        if (tableExists(c, "Payments")) {
                            String txRef = "CNPAY-" + System.currentTimeMillis() + "-" + bookingId;
                            try (PreparedStatement ps = c.prepareStatement(
                                    "INSERT INTO Payments (booking_id, amount, currency, payment_method, transaction_ref, gateway, status, paid_at) VALUES (?,?,?,?,?,'CloudNodePay','Success',NOW())")) {
                                ps.setInt(1, bookingId);
                                ps.setDouble(2, price);
                                ps.setString(3, "INR");
                                ps.setString(4, payMethod);
                                ps.setString(5, txRef);
                                ps.executeUpdate();
                            }
                        }

                        c.commit();
                    } catch (Exception e) {
                        c.rollback();
                        throw e;
                    } finally {
                        c.setAutoCommit(true);
                    }
                }

                send(ex, 200, "application/json",
                    "{\"ok\":true,\"message\":\"Booking confirmed\",\"bookingId\":" + bookingId +
                    ",\"pnr\":\"" + pnr + "\",\"amount\":" + price + "}");
            } catch (Exception e) {
                send(ex, 400, "application/json", "{\"error\":\"" + esc(e.getMessage()) + "\"}");
            }
        });

        server.createContext("/api/cancel", ex -> {
            cors(ex);
            if (opt(ex)) return;
            try {
                Map<String,String> p = parseBody(ex);
                int id = Integer.parseInt(req(p, "bookingId"));
                try (Connection c = db();
                     PreparedStatement ps = c.prepareStatement(
                         "UPDATE Bookings SET status='Cancelled' WHERE booking_id=? AND status!='Cancelled'")) {
                    ps.setInt(1, id);
                    if (ps.executeUpdate() == 0) throw new IllegalArgumentException("Booking not found or already cancelled");
                }
                send(ex, 200, "application/json", "{\"ok\":true,\"message\":\"Booking cancelled\"}");
            } catch (Exception e) {
                send(ex, 400, "application/json", "{\"error\":\"" + esc(e.getMessage()) + "\"}");
            }
        });

        server.createContext("/api/passengers/add", ex -> {
            cors(ex);
            if (opt(ex)) return;
            try {
                Map<String,String> p = parseBody(ex);
                String first = req(p, "firstName");
                String last = req(p, "lastName");
                String email = req(p, "email");
                String phone = p.getOrDefault("phone", "");
                try (Connection c = db();
                     PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO Passengers (first_name, last_name, email, phone) VALUES (?,?,?,?)",
                         Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, first);
                    ps.setString(2, last);
                    ps.setString(3, email);
                    ps.setString(4, phone);
                    ps.executeUpdate();
                    ResultSet keys = ps.getGeneratedKeys();
                    keys.next();
                    send(ex, 200, "application/json", "{\"ok\":true,\"passengerId\":" + keys.getInt(1) + "}");
                }
            } catch (SQLIntegrityConstraintViolationException e) {
                send(ex, 400, "application/json", "{\"error\":\"Email already exists\"}");
            } catch (Exception e) {
                send(ex, 400, "application/json", "{\"error\":\"" + esc(e.getMessage()) + "\"}");
            }
        });

        server.start();
        System.out.println();
        System.out.println("========================================");
        System.out.println("  Cloud Node Flight Booking is running");
        System.out.println("  http://localhost:" + port);
        System.out.println("========================================");
        openBrowser("http://localhost:" + port);
    }

    // ---------- helpers ----------
    interface RowFn { String map(ResultSet rs) throws SQLException; }

    static String query(String sql, RowFn fn) {
        try (Connection c = db(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return fn.map(rs);
        } catch (SQLException e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    static List<String> databaseTables(Connection c) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getTables(c.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rs.next()) names.add(rs.getString("TABLE_NAME"));
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    static String quoteIdentifier(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    static String queryParam(String rawQuery, String key) {
        if (rawQuery == null) return "";
        for (String part : rawQuery.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && key.equals(URLDecoder.decode(kv[0], StandardCharsets.UTF_8))) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    static Map<String,Object> runReadOnlySql(String sql) throws SQLException {
        List<String> columns = new ArrayList<>();
        List<Map<String,Object>> rows = new ArrayList<>();
        boolean truncated = false;
        try (Connection c = db(); Statement st = c.createStatement()) {
            st.setMaxRows(1001);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) columns.add(md.getColumnLabel(i));
                while (rs.next()) {
                    if (rows.size() == 1000) { truncated = true; break; }
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) row.put(columns.get(i - 1), rs.getObject(i));
                    rows.add(row);
                }
            }
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("columns", columns); result.put("rows", rows);
        result.put("rowCount", rows.size()); result.put("truncated", truncated);
        return result;
    }

    static List<Map<String,Object>> flightQueryPresets() {
        String[][] data = {
            {"Flight and airline directory", "JOIN", "Flights with airline names and route details.",
             "SELECT f.flight_number, a.airline_name, f.departure_city, f.arrival_city, f.departure_time, f.arrival_time, f.capacity, f.base_price FROM Flights f JOIN Airlines a ON a.airline_id=f.airline_id ORDER BY f.departure_time LIMIT 100;"},
            {"Passenger booking manifest", "MULTI-TABLE JOIN", "Bookings joined to passengers, flights, and airlines.",
             "SELECT b.booking_id, CONCAT(p.first_name,' ',p.last_name) AS passenger, f.flight_number, a.airline_name, f.departure_city, f.arrival_city, b.seat_number, b.status, b.total_amount FROM Bookings b JOIN Passengers p ON p.passenger_id=b.passenger_id JOIN Flights f ON f.flight_id=b.flight_id JOIN Airlines a ON a.airline_id=f.airline_id ORDER BY b.booking_date DESC;"},
            {"Revenue by airline", "AGGREGATE", "Confirmed booking revenue grouped by airline.",
             "SELECT a.airline_name, COUNT(b.booking_id) AS confirmed_bookings, COALESCE(SUM(b.total_amount),0) AS revenue FROM Airlines a LEFT JOIN Flights f ON f.airline_id=a.airline_id LEFT JOIN Bookings b ON b.flight_id=f.flight_id AND b.status='Confirmed' GROUP BY a.airline_id, a.airline_name ORDER BY revenue DESC;"},
            {"Flights with no bookings", "SUBQUERY", "Finds flights without any booking records.",
             "SELECT f.flight_id, f.flight_number, f.departure_city, f.arrival_city, f.departure_time FROM Flights f WHERE NOT EXISTS (SELECT 1 FROM Bookings b WHERE b.flight_id=f.flight_id) ORDER BY f.departure_time LIMIT 100;"},
            {"Route demand", "GROUP BY", "Counts bookings and revenue for each route.",
             "SELECT f.departure_city, f.arrival_city, COUNT(b.booking_id) AS bookings, COALESCE(SUM(CASE WHEN b.status='Confirmed' THEN b.total_amount ELSE 0 END),0) AS revenue FROM Flights f LEFT JOIN Bookings b ON b.flight_id=f.flight_id GROUP BY f.departure_city, f.arrival_city ORDER BY bookings DESC, revenue DESC LIMIT 50;"},
            {"Seat occupancy by flight", "AGGREGATE", "Compares confirmed bookings with each flight's capacity.",
             "SELECT f.flight_number, f.departure_city, f.arrival_city, f.capacity, COUNT(CASE WHEN b.status='Confirmed' THEN 1 END) AS confirmed_seats, f.capacity-COUNT(CASE WHEN b.status='Confirmed' THEN 1 END) AS remaining_seats FROM Flights f LEFT JOIN Bookings b ON b.flight_id=f.flight_id GROUP BY f.flight_id, f.flight_number, f.departure_city, f.arrival_city, f.capacity ORDER BY confirmed_seats DESC LIMIT 100;"},
            {"Booking status summary", "GROUP BY", "Summarizes booking counts and value by status.",
             "SELECT status, COUNT(*) AS booking_count, COALESCE(SUM(total_amount),0) AS total_value FROM Bookings GROUP BY status ORDER BY booking_count DESC;"},
            {"Flights per airline", "LEFT JOIN", "Includes airlines with no scheduled flights.",
             "SELECT a.airline_name, a.iata_code, COUNT(f.flight_id) AS total_flights FROM Airlines a LEFT JOIN Flights f ON f.airline_id=a.airline_id GROUP BY a.airline_id, a.airline_name, a.iata_code ORDER BY total_flights DESC;"},
            {"Flight availability", "FILTER", "Shows all flights and seats remaining based on active bookings.",
             "SELECT f.flight_number, f.departure_city, f.arrival_city, f.departure_time, f.capacity, COUNT(CASE WHEN b.status='Confirmed' THEN 1 END) AS booked, f.capacity-COUNT(CASE WHEN b.status='Confirmed' THEN 1 END) AS available FROM Flights f LEFT JOIN Bookings b ON b.flight_id=f.flight_id GROUP BY f.flight_id, f.flight_number, f.departure_city, f.arrival_city, f.departure_time, f.capacity ORDER BY f.departure_time LIMIT 100;"}
        };
        List<Map<String,Object>> presets = new ArrayList<>();
        for (int i = 0; i < data.length; i++) {
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("id", "q" + (i + 1)); item.put("title", data[i][0]);
            item.put("category", data[i][1]); item.put("description", data[i][2]); item.put("sql", data[i][3]);
            presets.add(item);
        }
        return presets;
    }

    static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? "Request failed" : message;
    }

    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value instanceof Map<?,?> map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?,?> entry : map.entrySet()) {
                if (!first) out.append(','); first = false;
                out.append(json(String.valueOf(entry.getKey()))).append(':').append(json(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable<?> values) {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (Object item : values) { if (!first) out.append(','); first = false; out.append(json(item)); }
            return out.append(']').toString();
        }
        String text = String.valueOf(value);
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch)); else out.append(ch); }
            }
        }
        return out.append('"').toString();
    }

    static String arr(ResultSet rs, RowFn row) throws SQLException {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        while (rs.next()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(row.map(rs));
        }
        return sb.append("]").toString();
    }

    static String obj(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(kv[i]).append("\":");
            Object v = kv[i + 1];
            if (v instanceof Number) sb.append(v);
            else sb.append("\"").append(esc(String.valueOf(v))).append("\"");
        }
        return sb.append("}").toString();
    }

    static Map<String,String> parseBody(HttpExchange ex) throws IOException {
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String,String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        // form-urlencoded
        if (!raw.trim().startsWith("{")) {
            for (String part : raw.split("&")) {
                String[] kv = part.split("=", 2);
                m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                      kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
            return m;
        }
        // minimal JSON key:value extraction
        String s = raw.replaceAll("[\\{\\}\"]", "");
        for (String part : s.split(",")) {
            String[] kv = part.split(":", 2);
            if (kv.length == 2) m.put(kv[0].trim(), kv[1].trim());
        }
        return m;
    }

    static String req(Map<String,String> p, String key) {
        String v = p.get(key);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException("Missing: " + key);
        return v.trim();
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type + "; charset=utf-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static void cors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    static boolean opt(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return true;
        }
        return false;
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return;
            }
        } catch (Exception ignored) {}
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) new ProcessBuilder("cmd", "/c", "start", url).start();
            else if (os.contains("mac")) new ProcessBuilder("open", url).start();
            else new ProcessBuilder("xdg-open", url).start();
        } catch (Exception e) {
            System.out.println("Open manually: " + url);
        }
    }
}
