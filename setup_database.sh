#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

mysql --host="${DB_HOST:-localhost}" \
  --port="${DB_PORT:-3306}" \
  --user="${DB_USER:-root}" \
  --password <<'SQL'
SOURCE flight_booking_extensions.sql;
SELECT CONCAT('Flights in flight_booking: ', COUNT(*)) AS result
FROM flight_booking.Flights;
SQL
