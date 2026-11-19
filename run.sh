#!/usr/bin/env bash
# DEIHS MMS launcher for Linux/macOS.
#
#   ./run.sh                                  # use db.properties
#   JAVA_OPTS="-Ddb.host=192.168.1.10 -Ddb.port=3306" ./run.sh
#
set -euo pipefail
cd "$(dirname "$0")"

JAR="target/deihs-mms-1.0.0.jar"

if ! command -v java >/dev/null 2>&1; then
  echo "Java was not found. Install JDK 21 and try again." >&2
  exit 1
fi

if [ ! -f "$JAR" ]; then
  echo "Building the application (first run)..."
  mvn -q -B package -DskipTests
fi

exec java ${JAVA_OPTS:-} -jar "$JAR" "$@"
