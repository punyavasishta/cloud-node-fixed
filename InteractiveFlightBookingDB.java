import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.InputMismatchException;
import java.util.List;
import java.util.Scanner;

// ===================== CUSTOM EXCEPTIONS =====================
class NotFoundException extends Exception {
    public NotFoundException(String msg) { super(msg); }
}
class InvalidInputException extends Exception {
    public InvalidInputException(String msg) { super(msg); }
}
class DatabaseException extends Exception {
    public DatabaseException(String msg) { super(msg); }
}
class FlightFullException extends Exception {
    public FlightFullException(String msg) { super(msg); }
}
class SeatTakenException extends Exception {
    public SeatTakenException(String msg) { super(msg); }
}

// ===================== DATABASE CONNECTION =====================
class Database {
    private static final String URL  = env("DB_URL",  "jdbc:mysql://localhost:3306/flight_booking");
    private static final String USER = env("DB_USER", "root");
    private static final String PASS = env("DB_PASS", "Apple23@_");

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null) ? def : v;
    }

    public static Connection connect() throws DatabaseException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            return DriverManager.getConnection(URL, USER, PASS);
        } catch (ClassNotFoundException e) {
            throw new DatabaseException(
                "MySQL driver not found. Run with: java -cp \".:mysql-connector-j-26.7.0.jar\" InteractiveFlightBookingDB");
        } catch (SQLException e) {
            throw new DatabaseException("Could not connect to MySQL: " + e.getMessage()
                + "\nEdit PASS in Database class or set DB_PASS env.");
        }
    }
}

// ===================== MODEL CLASSES =====================
class Airline {
    private int airlineId;
    private String airlineName;
    private String country;
    private String iataCode;

    public Airline(int airlineId, String airlineName, String country, String iataCode) {
        this.airlineId = airlineId;
        this.airlineName = airlineName;
        this.country = country;
        this.iataCode = iataCode;
    }

    public int getAirlineId() { return airlineId; }
    public String getAirlineName() { return airlineName; }
    public String getCountry() { return country; }
    public String getIataCode() { return iataCode; }

    @Override
    public String toString() {
        return airlineName + " (" + iataCode + ") — " + country;
    }
}

class Flight {
    private int flightId;
    private String flightNumber;
    private String airlineName;
    private String departureCity;
    private String arrivalCity;
    private String departureTime;
    private String arrivalTime;
    private int capacity;
    private double basePrice;
    private int booked;
    private int available;

    public Flight(int flightId, String flightNumber, String airlineName,
                  String departureCity, String arrivalCity,
                  String departureTime, String arrivalTime,
                  int capacity, double basePrice, int booked) {
        this.flightId = flightId;
        this.flightNumber = flightNumber;
        this.airlineName = airlineName;
        this.departureCity = departureCity;
        this.arrivalCity = arrivalCity;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.capacity = capacity;
        this.basePrice = basePrice;
        this.booked = booked;
        this.available = capacity - booked;
    }

    public int getFlightId() { return flightId; }
    public String getFlightNumber() { return flightNumber; }
    public String getAirlineName() { return airlineName; }
    public String getDepartureCity() { return departureCity; }
    public String getArrivalCity() { return arrivalCity; }
    public String getDepartureTime() { return departureTime; }
    public String getArrivalTime() { return arrivalTime; }
    public int getCapacity() { return capacity; }
    public double getBasePrice() { return basePrice; }
    public int getBooked() { return booked; }
    public int getAvailable() { return available; }

    public void display() {
        System.out.println("ID: " + flightId
            + " | " + flightNumber + " (" + airlineName + ")"
            + " | " + departureCity + " → " + arrivalCity
            + " | Dep: " + departureTime
            + " | Seats: " + available + "/" + capacity
            + " | ₹" + String.format("%.2f", basePrice));
    }
}

class Passenger {
    private int passengerId;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;

    public Passenger(int passengerId, String firstName, String lastName, String email, String phone) {
        this.passengerId = passengerId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.phone = phone;
    }

    public int getPassengerId() { return passengerId; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getFullName() { return firstName + " " + lastName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }

    public void display() {
        System.out.println("ID: " + passengerId
            + " | " + getFullName()
            + " | " + email
            + (phone != null && !phone.isEmpty() ? " | " + phone : ""));
    }
}

class Booking {
    private int bookingId;
    private String passengerName;
    private String flightNumber;
    private String airlineName;
    private String route;
    private String departureTime;
    private String seatNumber;
    private String status;
    private double totalAmount;
    private String bookingDate;

    public Booking(int bookingId, String passengerName, String flightNumber, String airlineName,
                   String route, String departureTime, String seatNumber, String status,
                   double totalAmount, String bookingDate) {
        this.bookingId = bookingId;
        this.passengerName = passengerName;
        this.flightNumber = flightNumber;
        this.airlineName = airlineName;
        this.route = route;
        this.departureTime = departureTime;
        this.seatNumber = seatNumber;
        this.status = status;
        this.totalAmount = totalAmount;
        this.bookingDate = bookingDate;
    }

    public int getBookingId() { return bookingId; }
    public String getStatus() { return status; }

    public void display() {
        System.out.println("Booking #" + bookingId
            + " | " + passengerName
            + " | " + flightNumber + " (" + airlineName + ")"
            + " | " + route
            + " | " + departureTime
            + " | Seat " + seatNumber
            + " | " + status
            + " | ₹" + String.format("%.2f", totalAmount)
            + " | Booked: " + bookingDate);
    }
}

// ===================== AIRLINE MANAGER =====================
class AirlineManager {
    public List<Airline> getAll() throws DatabaseException {
        List<Airline> list = new ArrayList<>();
        String sql = "SELECT airline_id, airline_name, country, iata_code FROM Airlines ORDER BY airline_name";
        try (Connection c = Database.connect();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Airline(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        } catch (SQLException e) {
            throw new DatabaseException("Database error: " + e.getMessage());
        }
        return list;
    }

    public void displayAll() throws DatabaseException {
        List<Airline> airlines = getAll();
        if (airlines.isEmpty()) {
            System.out.println("No airlines found.");
            return;
        }
        System.out.println("\n--- AIRLINES ---");
        for (Airline a : airlines) {
            System.out.println(a.getAirlineId() + ". " + a);
        }
    }

    public void displayFlightCounts() throws DatabaseException {
        String sql =
            "SELECT a.airline_name, a.iata_code, COUNT(f.flight_id) AS total "
          + "FROM Airlines a LEFT JOIN Flights f ON a.airline_id = f.airline_id "
          + "GROUP BY a.airline_id, a.airline_name, a.iata_code ORDER BY total DESC";
        try (Connection c = Database.connect();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            System.out.println("\n--- FLIGHTS PER AIRLINE ---");
            while (rs.next()) {
                System.out.println(rs.getString(1) + " (" + rs.getString(2) + "): " + rs.getInt(3) + " flights");
            }
        } catch (SQLException e) {
            throw new DatabaseException("Database error: " + e.getMessage());
        }
    }
}

// ===================== FLIGHT MANAGER =====================
class FlightManager {
    private static final String SELECT =
        "SELECT f.flight_id, f.flight_number, a.airline_name, f.departure_city, f.arrival_city, "
      + "DATE_FORMAT(f.departure_time, '%d-%b-%Y %H:%i') AS dep, "
      + "DATE_FORMAT(f.arrival_time, '%d-%b-%Y %H:%i') AS arr, "
      + "f.capacity, f.base_price, "
      + "(SELECT COUNT(*) FROM Bookings b WHERE b.flight_id = f.flight_id AND b.status != 'Cancelled') AS booked "
      + "FROM Flights f JOIN Airlines a ON f.airline_id = a.airline_id ";

    private Flight mapRow(ResultSet rs) throws SQLException {
        return new Flight(
            rs.getInt("flight_id"), rs.getString("flight_number"), rs.getString("airline_name"),
            rs.getString("departure_city"), rs.getString("arrival_city"),
            rs.getString("dep"), rs.getString("arr"),
            rs.getInt("capacity"), rs.getDouble("base_price"), rs.getInt("booked")
        );
    }

    private List<Flight> query(String where, Object... args) throws DatabaseException {
        List<Flight> list = new ArrayList<>();
        try (Connection c = Database.connect();
             PreparedStatement ps = c.prepareStatement(SELECT + where)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapRow(rs));
        } catch (SQLException e) {
            throw new DatabaseException("Database error: " + e.getMessage());
        }
        return list;
    }

    public List<Flight> getAll() throws DatabaseException {
        return query("ORDER BY f.departure_time");
    }

    public Flight findById(int id) throws NotFoundException, DatabaseException {
        List<Flight> found = query("WHERE f.flight_id = ?", id);
        if (found.isEmpty()) throw new NotFoundException("Flight ID " + id + " not found.");
        return found.get(0);
    }

    public List<Flight> search(String from, String to, String datePrefix) throws DatabaseException {
        StringBuilder where = new StringBuilder("WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (from != null && !from.isEmpty()) {
            where.append("AND f.departure_city = ? ");
            args.add(from);
        }
        if (to != null && !to.isEmpty()) {
            where.append("AND f.arrival_city = ? ");
            args.add(to);
        }
        if (datePrefix != null && !datePrefix.isEmpty()) {
            where.append("AND DATE(f.departure_time) = ? ");
            args.add(datePrefix);
        }
        where.append("ORDER BY f.departure_time");
        return query(where.toString(), args.toArray());
    }

    public void displayAll() throws DatabaseException {
        List<Flight> flights = getAll();
        if (flights.isEmpty()) {
            System.out.println("No flights in the system.");
            return;
        }
        System.out.println("\n--- ALL FLIGHTS (" + flights.size() + ") ---");
        for (Flight f : flights) {
            f.display();
            System.out.println("-----------------------");
        }
    }

    public void displaySearch(String from, String to, String date) throws DatabaseException {
        List<Flight> flights = search(from, to, date);
        if (flights.isEmpty()) {
            System.out.println("No matching flights found.");
            return;
        }
        System.out.println("\n--- SEARCH RESULTS (" + flights.size() + ") ---");
        for (Flight f : flights) {
            f.display();
            System.out.println("-----------------------");
        }
    }

    public void displayUnbooked() throws DatabaseException {
        String sql =
            "SELECT f.flight_number, a.airline_name, f.departure_city, f.arrival_city, "
          + "DATE_FORMAT(f.departure_time, '%d-%b-%Y %H:%i') "
          + "FROM Flights f JOIN Airlines a ON f.airline_id = a.airline_id "
          + "LEFT JOIN Bookings b ON f.flight_id = b.flight_id "
          + "WHERE b.booking_id IS NULL ORDER BY f.departure_time";
        try (Connection c = Database.connect();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            System.out.println("\n--- FLIGHTS WITH NO BOOKINGS ---");
            int n = 0;
            while (rs.next()) {
                n++;
                System.out.println(rs.getString(1) + " | " + rs.getString(2)
                    + " | " + rs.getString(3) + " → " + rs.getString(4)
                    + " | " + rs.getString(5));
            }
            if (n == 0) System.out.println("Every flight has at least one booking.");
            else System.out.println("Total: " + n);
        } catch (SQLException e) {
            throw new DatabaseException("Database error: " + e.getMessage());
        }
    }

    public void displayTopFlights(int limit) throws DatabaseException {
        String sql =
            "SELECT f.flight_number, a.airline_name, "
          + "CONCAT(f.departure_city, ' → ', f.arrival_city), COUNT(b.booking_id) AS cnt "
          + "FROM Flights f "
          + "JOIN Airlines a ON f.airline_id = a.airline_id "
          + "JOIN Bookings b ON f.flight_id = b.flight_id "
          + "GROUP BY f.flight_id, f.flight_number, a.airline_name, f.departure_city, f.arrival_city "
          + "ORDER BY cnt DESC LIMIT ?";
        try (Connection c = Database.connect();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            System.out.println("\n--- TOP " + limit + " MOST BOOKED FLIGHTS ---");
            int rank = 1;
            while (rs.next()) {
                System.out.println(rank + ". " + rs.getString(1) + " (" + rs.getString(2) + ") "
                    + rs.getString(3) + " — " + rs.getInt(4) + " bookings");
                rank++;
            }
            if (rank == 1) System.out.println("No bookings yet.");
        } catch (SQLException e) {
            throw new DatabaseException("Database error: " + e.getMessage());
        }
    }
}

// ===================== PASSENGER MANAGER =====================
class PassengerManager {
    public List<Passenger> getAll() throws DatabaseException {
        List<Passenger> list = new ArrayList<>();
        String sql = "SELECT passenger_id, first_name, last_name, email, phone FROM Passengers ORDER BY first_name, last_name";
        try (Connection c = Database.connect();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Passenger(rs.getInt(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5)));
            }
        } catch (SQLException e) {
            throw new DatabaseException("Database error: " + e.getMessage());
        }
        return list;
    }

    public Passenger findById(int id) throws NotFoundException, DatabaseException {
        String sql = "SELECT passenger_id, first_name, last_name, email, phone FROM Passengers WHERE passenger_id=?";
        try (Connection c = Database.connect();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new Passenger(rs.getInt(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5));
            }
        } catch (SQLException e) {
            throw new DatabaseException("Database error: " + e.getMessage());
        }
        throw new NotFoundException("Passenger ID " + id + " not found.");
    }

    public int add(String first, String last, String email, String phone)
            throws InvalidInputException, DatabaseException {
        if (first == null || first.trim().isEmpty() || last == null || last.trim().isEmpty()) {
            throw new InvalidInputException("First name and last name are required.");
        }
        if (email == null || email.trim().isEmpty()) {
            throw new InvalidInputException("Email is required.");
        }
        String sql = "INSERT INTO Passengers (first_name, last_name, email, phone) VALUES (?,?,?,?)";
        try (Connection c = Database.connect();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, first.trim());
            ps.setString(2, last.trim());
            ps.setString(3, email.trim());
            ps.setString(4, phone == null ? "" : phone.trim());
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            int id = keys.getInt(1);
            System.out.println("Passenger added with ID: " + id);
            return id;
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new InvalidInputException("Email already exists.");
        } catch (SQLException e) {
            throw new DatabaseException("Database error: " + e.getMessage());
        }
    }

    public void displayAll() throws DatabaseException {
        List<Passenger> list = getAll();
        if (list.isEmpty()) {
            System.out.println("No passengers registered.");
            return;
        }
        System.out.println("\n--- PASSENGERS (" + list.size() + ") ---");
        for (Passenger p : list) {
            p.display();
        }
    }
}

// ===================== BOOKING MANAGER =====================
class BookingManager {
    private FlightManager flightManager = new FlightManager();
    private PassengerManager passengerManager = new PassengerManager();

    public List<Booking> getAll() throws DatabaseException {
        List<Booking> list = new ArrayList<>();
        String sql =
            "SELECT b.booking_id, CONCAT(p.first_name,' ',p.last_name), f.flight_number, a.airline_name, "
          + "CONCAT(f.departure_city,' → ',f.arrival_city), "
          + "DATE_FORMAT(f.departure_time,'%d-%b-%Y %H:%i'), "
          + "b.seat_number, b.status, b.total_amount, "
          + "DATE_FORMAT(b.booking_date,'%d-%b-%Y %H:%i') "
          + "FROM Bookings b "
          + "JOIN Passengers p ON b.passenger_id = p.passenger_id "
          + "JOIN Flights f ON b.flight_id = f.flight_id "
          + "JOIN Airlines a ON f.airline_id = a.airline_id "
          + "ORDER BY b.booking_date DESC";
        try (Connection c = Database.connect();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Booking(
                    rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                    rs.getDouble(9), rs.getString(10)
                ));
            }
        } catch (SQLException e) {
            throw new DatabaseException("Database error: " + e.getMessage());
        }
        return list;
    }

    public void displayAll() throws DatabaseException {
        List<Booking> list = getAll();
        if (list.isEmpty()) {
            System.out.println("No bookings found.");
            return;
        }
        System.out.println("\n--- ALL BOOKINGS (" + list.size() + ") ---");
        for (Booking b : list) {
            b.display();
            System.out.println("-----------------------");
        }
    }

    public int book(int passengerId, int flightId, String seat)
            throws NotFoundException, FlightFullException, SeatTakenException,
                   InvalidInputException, DatabaseException {
        if (seat == null || seat.trim().isEmpty()) {
            throw new InvalidInputException("Seat number is required (e.g. 12A).");
        }
        seat = seat.trim().toUpperCase();

        // Ensure passenger exists
        passengerManager.findById(passengerId);

        try (Connection c = Database.connect()) {
            int capacity, booked;
            double price;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT capacity, base_price, "
                  + "(SELECT COUNT(*) FROM Bookings b WHERE b.flight_id=? AND b.status!='Cancelled') "
                  + "FROM Flights WHERE flight_id=?")) {
                ps.setInt(1, flightId);
                ps.setInt(2, flightId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) throw new NotFoundException("Flight ID " + flightId + " not found.");
                capacity = rs.getInt(1);
                price = rs.getDouble(2);
                booked = rs.getInt(3);
            }
            if (booked >= capacity) {
                throw new FlightFullException("Flight is full (" + booked + "/" + capacity + ").");
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM Bookings WHERE flight_id=? AND seat_number=? AND status!='Cancelled'")) {
                ps.setInt(1, flightId);
                ps.setString(2, seat);
                ResultSet rs = ps.executeQuery();
                rs.next();
                if (rs.getInt(1) > 0) {
                    throw new SeatTakenException("Seat " + seat + " is already taken on this flight.");
                }
            }

            int bookingId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO Bookings (passenger_id, flight_id, seat_number, status, total_amount) "
                  + "VALUES (?,?,?,'Confirmed',?)",
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

            String pnr = "CN" + String.format("%06d", bookingId);
            System.out.println("Booking confirmed!");
            System.out.println("  Booking ID : " + bookingId);
            System.out.println("  PNR        : " + pnr);
            System.out.println("  Seat       : " + seat);
            System.out.println("  Amount     : ₹" + String.format("%.2f", price));
            return bookingId;
        } catch (SQLException e) {
            throw new DatabaseException("Database error: " + e.getMessage());
        }
    }

    public void cancel(int bookingId) throws NotFoundException, InvalidInputException, DatabaseException {
        try (Connection c = Database.connect();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE Bookings SET status='Cancelled' WHERE booking_id=? AND status!='Cancelled'")) {
            ps.setInt(1, bookingId);
            if (ps.executeUpdate() == 0) {
                throw new NotFoundException("Booking not found or already cancelled.");
            }
            System.out.println("Booking #" + bookingId + " cancelled successfully.");
        } catch (SQLException e) {
            throw new DatabaseException("Database error: " + e.getMessage());
        }
    }
}

// ===================== MAIN =====================
public class InteractiveFlightBookingDB {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        AirlineManager airlineManager = new AirlineManager();
        FlightManager flightManager = new FlightManager();
        PassengerManager passengerManager = new PassengerManager();
        BookingManager bookingManager = new BookingManager();

        System.out.println("Connecting to MySQL (flight_booking)...");
        try (Connection c = Database.connect()) {
            System.out.println("Connected. Cloud Node Flight Booking console is ready.");
        } catch (DatabaseException | SQLException e) {
            System.out.println(e.getMessage());
            scanner.close();
            return;
        }

        boolean running = true;
        while (running) {
            try {
                System.out.println("\n========== CLOUD NODE FLIGHT BOOKING ==========");
                System.out.println("1.  View all flights");
                System.out.println("2.  Search flights (from / to / date)");
                System.out.println("3.  View airlines & flight counts");
                System.out.println("4.  View unbooked flights");
                System.out.println("5.  Top 3 most booked flights");
                System.out.println("6.  View all passengers");
                System.out.println("7.  Register new passenger");
                System.out.println("8.  Book a flight");
                System.out.println("9.  View all bookings");
                System.out.println("10. Cancel a booking");
                System.out.println("11. Exit");
                System.out.print("Select an option: ");

                int choice = scanner.nextInt();
                scanner.nextLine();

                switch (choice) {
                    case 1:
                        flightManager.displayAll();
                        break;

                    case 2: {
                        System.out.print("From city (blank = any): ");
                        String from = scanner.nextLine().trim();
                        System.out.print("To city (blank = any): ");
                        String to = scanner.nextLine().trim();
                        System.out.print("Date YYYY-MM-DD (blank = any): ");
                        String date = scanner.nextLine().trim();
                        if (!from.isEmpty() && !to.isEmpty() && from.equalsIgnoreCase(to)) {
                            System.out.println("From and To must be different cities.");
                            break;
                        }
                        flightManager.displaySearch(from, to, date);
                        break;
                    }

                    case 3:
                        airlineManager.displayAll();
                        airlineManager.displayFlightCounts();
                        break;

                    case 4:
                        flightManager.displayUnbooked();
                        break;

                    case 5:
                        flightManager.displayTopFlights(3);
                        break;

                    case 6:
                        passengerManager.displayAll();
                        break;

                    case 7: {
                        System.out.print("First name: ");
                        String fn = scanner.nextLine().trim();
                        System.out.print("Last name: ");
                        String ln = scanner.nextLine().trim();
                        System.out.print("Email: ");
                        String email = scanner.nextLine().trim();
                        System.out.print("Phone (optional): ");
                        String phone = scanner.nextLine().trim();
                        passengerManager.add(fn, ln, email, phone);
                        break;
                    }

                    case 8: {
                        passengerManager.displayAll();
                        System.out.print("Passenger ID: ");
                        int paxId = scanner.nextInt();
                        scanner.nextLine();

                        System.out.print("From city (blank = any): ");
                        String from = scanner.nextLine().trim();
                        System.out.print("To city (blank = any): ");
                        String to = scanner.nextLine().trim();
                        System.out.print("Date YYYY-MM-DD (blank = any): ");
                        String date = scanner.nextLine().trim();
                        flightManager.displaySearch(from, to, date);

                        System.out.print("Flight ID to book: ");
                        int flightId = scanner.nextInt();
                        scanner.nextLine();

                        Flight f = flightManager.findById(flightId);
                        if (f.getAvailable() <= 0) {
                            System.out.println("This flight has no open seats.");
                            break;
                        }
                        System.out.println("Selected: " + f.getFlightNumber() + " " + f.getDepartureCity()
                            + " → " + f.getArrivalCity() + " | ₹" + String.format("%.2f", f.getBasePrice())
                            + " | " + f.getAvailable() + " seats left");

                        System.out.print("Seat number (e.g. 12A): ");
                        String seat = scanner.nextLine().trim();
                        bookingManager.book(paxId, flightId, seat);
                        break;
                    }

                    case 9:
                        bookingManager.displayAll();
                        break;

                    case 10: {
                        bookingManager.displayAll();
                        System.out.print("Booking ID to cancel: ");
                        int bid = scanner.nextInt();
                        scanner.nextLine();
                        System.out.print("Confirm cancel booking #" + bid + "? (y/n): ");
                        String conf = scanner.nextLine().trim();
                        if (conf.equalsIgnoreCase("y")) {
                            bookingManager.cancel(bid);
                        } else {
                            System.out.println("Cancel aborted.");
                        }
                        break;
                    }

                    case 11:
                        System.out.println("Exiting Cloud Node Flight Booking. Goodbye!");
                        running = false;
                        break;

                    default:
                        System.out.println("Invalid option. Choose 1–11.");
                }
            } catch (InputMismatchException e) {
                System.out.println("Input Error: please enter a valid number.");
                scanner.nextLine();
            } catch (InvalidInputException | NotFoundException | FlightFullException
                     | SeatTakenException | DatabaseException e) {
                System.out.println("Operation Failed: " + e.getMessage());
            }
        }
        scanner.close();
    }
}
