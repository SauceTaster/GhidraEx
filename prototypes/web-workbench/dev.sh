#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_JAVA="$PROJECT_DIR/../../.toolchains/jdk-25/Contents/Home"
SERVICE_PORT="${GHIDRAEX_SERVICE_PORT:-18787}"
SYNTHETIC_READINESS_URL="${GHIDRAEX_READINESS_URL:-http://127.0.0.1:${SERVICE_PORT}/api/v1/snapshot}"
SYNTHETIC_READINESS_TIMEOUT_SECONDS="${GHIDRAEX_READINESS_TIMEOUT_SECONDS:-45}"
GATEWAY_STARTUP_TIMEOUT_SECONDS="${GHIDRAEX_GATEWAY_STARTUP_TIMEOUT_SECONDS:-600}"
GATEWAY_READINESS_TIMEOUT_SECONDS="${GHIDRAEX_GATEWAY_READINESS_TIMEOUT_SECONDS:-630}"
BINARY="${1:-${GHIDRAEX_BINARY:-}}"

if (( $# > 1 )); then
  echo "Usage: ./dev.sh [native-binary]" >&2
  exit 2
fi

if ! command -v npm >/dev/null 2>&1; then
  echo "Node.js and npm are required." >&2
  exit 1
fi

require_positive_integer() {
  local name="$1"
  local value="$2"
  if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
    echo "$name must be a positive integer." >&2
    exit 2
  fi
}

cleanup() {
  if [[ -n "${BACKEND_PID:-}" ]]; then
    kill "$BACKEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi
  if [[ -n "${RUNTIME_DIR:-}" ]]; then
    rm -rf "$RUNTIME_DIR"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

start_synthetic_backend() {
  if ! java -version >/dev/null 2>&1 && [[ -x "$WORKSPACE_JAVA/bin/java" ]]; then
    export JAVA_HOME="$WORKSPACE_JAVA"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi

  if ! java -version >/dev/null 2>&1; then
    echo "JDK 25 is required for synthetic mode. Set JAVA_HOME to a JDK 25 installation." >&2
    exit 1
  fi
  if ! command -v curl >/dev/null 2>&1; then
    echo "curl is required to verify synthetic service readiness." >&2
    exit 1
  fi
  require_positive_integer "GHIDRAEX_READINESS_TIMEOUT_SECONDS" "$SYNTHETIC_READINESS_TIMEOUT_SECONDS"
  if [[ ! "$SERVICE_PORT" =~ ^[1-9][0-9]{0,4}$ ]] || (( 10#$SERVICE_PORT > 65535 )); then
    echo "GHIDRAEX_SERVICE_PORT must be an integer between 1 and 65535." >&2
    exit 2
  fi

  export GHIDRAEX_SERVICE_PORT="$SERVICE_PORT"
  export VITE_GHIDRAEX_ENGINE_MODE="synthetic"
  (cd "$PROJECT_DIR/server" && ./gradlew --quiet run --args="--port=$SERVICE_PORT") &
  BACKEND_PID=$!

  local deadline=$((SECONDS + SYNTHETIC_READINESS_TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
      wait "$BACKEND_PID" 2>/dev/null || true
      echo "The JVM service stopped before it became ready; the frontend was not started." >&2
      exit 1
    fi
    local snapshot
    snapshot="$(curl --silent --fail --connect-timeout 1 --max-time 2 "$SYNTHETIC_READINESS_URL" 2>/dev/null || true)"
    if [[ "$snapshot" == *'"apiVersion":1'* && "$snapshot" == *'"project"'* ]]; then
      return
    fi
    sleep 0.2
  done
  echo "The JVM service did not become ready within ${SYNTHETIC_READINESS_TIMEOUT_SECONDS}s; the frontend was not started." >&2
  exit 1
}

start_real_backend() {
  if ! command -v python3 >/dev/null 2>&1 \
      || ! python3 -c 'import sys; raise SystemExit(sys.version_info < (3, 10))'; then
    echo "Python 3.10 or later is required for real-engine mode." >&2
    exit 1
  fi
  if [[ ! -f "$BINARY" ]]; then
    echo "Real-engine input is not a regular file: $BINARY" >&2
    exit 2
  fi
  require_positive_integer "GHIDRAEX_GATEWAY_STARTUP_TIMEOUT_SECONDS" "$GATEWAY_STARTUP_TIMEOUT_SECONDS"
  require_positive_integer "GHIDRAEX_GATEWAY_READINESS_TIMEOUT_SECONDS" "$GATEWAY_READINESS_TIMEOUT_SECONDS"

  RUNTIME_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ghidraex-web.XXXXXXXX")"
  chmod 700 "$RUNTIME_DIR"
  local metadata_file="$RUNTIME_DIR/gateway.json"
  local gateway="$PROJECT_DIR/../../integration/gateway/gateway.py"
  python3 "$gateway" "$BINARY" \
    --origin "http://127.0.0.1:5173" \
    --metadata-file "$metadata_file" \
    --parent-pid "$$" \
    --startup-timeout "$GATEWAY_STARTUP_TIMEOUT_SECONDS" &
  BACKEND_PID=$!

  local deadline=$((SECONDS + GATEWAY_READINESS_TIMEOUT_SECONDS))
  while [[ ! -f "$metadata_file" && SECONDS -lt deadline ]]; do
    if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
      wait "$BACKEND_PID" 2>/dev/null || true
      echo "The Ghidra gateway stopped before publishing its private descriptor." >&2
      exit 1
    fi
    sleep 0.2
  done
  if [[ ! -f "$metadata_file" ]]; then
    echo "The Ghidra gateway did not become ready within ${GATEWAY_READINESS_TIMEOUT_SECONDS}s." >&2
    exit 1
  fi

  python3 -c 'import json,sys; data=json.load(open(sys.argv[1], encoding="utf-8")); assert data.get("pid") == int(sys.argv[2]) and data.get("protocolVersion") == 1' "$metadata_file" "$BACKEND_PID" \
    || { echo "The Ghidra gateway descriptor did not match the launched process." >&2; exit 1; }
  GHIDRAEX_GATEWAY_ORIGIN="$(python3 -c 'import json,sys; value=json.load(open(sys.argv[1], encoding="utf-8"))["baseUrl"]; assert isinstance(value,str); print(value)' "$metadata_file")"
  GHIDRAEX_GATEWAY_TOKEN="$(python3 -c 'import json,sys; value=json.load(open(sys.argv[1], encoding="utf-8"))["token"]; assert isinstance(value,str); print(value)' "$metadata_file")"
  export GHIDRAEX_GATEWAY_ORIGIN GHIDRAEX_GATEWAY_TOKEN
  export VITE_GHIDRAEX_ENGINE_MODE="real"

  python3 -c 'import json,os,urllib.request; request=urllib.request.Request(os.environ["GHIDRAEX_GATEWAY_ORIGIN"] + "/v1/health", headers={"Authorization": "Bearer " + os.environ["GHIDRAEX_GATEWAY_TOKEN"]}); response=urllib.request.urlopen(request, timeout=5); data=json.load(response); assert response.status == 200 and data.get("status") == "ready" and data.get("protocolVersion") == 1' \
    || { echo "The authenticated Ghidra gateway health check failed." >&2; exit 1; }
}

if [[ -n "$BINARY" ]]; then
  start_real_backend
else
  start_synthetic_backend
fi

cd "$PROJECT_DIR"
npm run dev
