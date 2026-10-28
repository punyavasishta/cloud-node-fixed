#!/bin/bash
cd "$(dirname "$0")"
echo "Compiling FlightServer..."
javac -cp "mysql-connector-j-26.7.0.jar" FlightServer.java
if [ $? -ne 0 ]; then
  echo "Compile failed"
  exit 1
fi
if [ -z "${DB_PASS:-}" ]; then
  read -r -s -p "MySQL password for ${DB_USER:-root}: " DB_PASS
  echo
  export DB_PASS
fi
echo "Starting Cloud Node Flight Booking..."
echo "The app will open at http://localhost:8080 after MySQL connects."
java -cp ".:mysql-connector-j-26.7.0.jar" FlightServer "$@"
