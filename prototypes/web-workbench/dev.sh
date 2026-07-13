#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_JAVA="$PROJECT_DIR/../../.toolchains/jdk-25/Contents/Home"
READINESS_URL="${GHIDRAEX_READINESS_URL:-http://127.0.0.1:8787/api/v1/snapshot}"
READINESS_TIMEOUT_SECONDS="${GHIDRAEX_READINESS_TIMEOUT_SECONDS:-45}"

if ! java -version >/dev/null 2>&1 && [[ -x "$WORKSPACE_JAVA/bin/java" ]]; then
  export JAVA_HOME="$WORKSPACE_JAVA"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if ! java -version >/dev/null 2>&1; then
  echo "JDK 25 is required. Set JAVA_HOME to a JDK 25 installation." >&2
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required to verify JVM service readiness." >&2
  exit 1
fi

if [[ ! "$READINESS_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
  echo "GHIDRAEX_READINESS_TIMEOUT_SECONDS must be a positive integer." >&2
  exit 1
fi

cleanup() {
  if [[ -n "${SERVICE_PID:-}" ]]; then
    kill "$SERVICE_PID" 2>/dev/null || true
    wait "$SERVICE_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

(cd "$PROJECT_DIR/server" && ./gradlew --quiet run) &
SERVICE_PID=$!

BACKEND_READY=0
READINESS_DEADLINE=$((SECONDS + READINESS_TIMEOUT_SECONDS))

while (( SECONDS < READINESS_DEADLINE )); do
  if ! kill -0 "$SERVICE_PID" 2>/dev/null; then
    wait "$SERVICE_PID" 2>/dev/null || true
    echo "The JVM service stopped before it became ready; the frontend was not started." >&2
    exit 1
  fi

  SNAPSHOT="$(curl --silent --fail --connect-timeout 1 --max-time 2 "$READINESS_URL" 2>/dev/null || true)"
  if [[ "$SNAPSHOT" == *'"apiVersion":1'* && "$SNAPSHOT" == *'"project"'* ]]; then
    BACKEND_READY=1
    break
  fi
  sleep 0.2
done

if (( BACKEND_READY != 1 )); then
  echo "The JVM service did not become ready within ${READINESS_TIMEOUT_SECONDS}s; the frontend was not started." >&2
  exit 1
fi

cd "$PROJECT_DIR"
npm run dev
