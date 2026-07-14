#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_JAVA="$ROOT/.toolchains/jdk-25/Contents/Home"
if [[ ! -x "$LOCAL_JAVA/bin/java" ]]; then
  LOCAL_JAVA="$ROOT/.toolchains/jdk-25"
fi

if [[ -z "${JAVA_HOME:-}" && -x "$LOCAL_JAVA/bin/java" ]]; then
  export JAVA_HOME="$LOCAL_JAVA"
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JDK 25 is required. Set JAVA_HOME or install it at $LOCAL_JAVA." >&2
  exit 1
fi

export PATH="$JAVA_HOME/bin:$PATH"

JAVA_SPECIFICATION_VERSION="$("$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1 \
  | awk '/java.specification.version =/ { print $3; exit }')"
if [[ "$JAVA_SPECIFICATION_VERSION" != "25" ]]; then
  echo "JDK 25 is required for the UI prototypes; JAVA_HOME reports $JAVA_SPECIFICATION_VERSION." >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1 \
    || ! python3 -c 'import sys; raise SystemExit(sys.version_info < (3, 10))'; then
  echo "Python 3.10 or later is required for the Ghidra bridge tests." >&2
  exit 1
fi

python3 "$ROOT/scripts/check-ui-read-boundaries.py"
python3 -W error::ResourceWarning -m unittest discover -s "$ROOT/scripts/tests" -v

(
  cd "$ROOT"
  python3 -W error::ResourceWarning -m unittest discover -s integration/protocol/tests -v
  python3 -W error::ResourceWarning -m unittest discover -s integration/ghidra/tests -v
  python3 -W error::ResourceWarning -m unittest discover -s integration/gateway/tests -v
  ./prototypes/javafx-workbench/gradlew -p integration/view-state/java clean test
)

(
  cd "$ROOT/prototypes/javafx-workbench"
  ./gradlew clean build
)

(
  cd "$ROOT/prototypes/web-workbench"
  if [[ "${CI:-}" == "true" || ! -d node_modules ]]; then
    npm ci
  fi
  npm test
  npm run build
  cd server
  ./gradlew clean test
)

(
  cd "$ROOT/prototypes/intellij-workbench"
  ./gradlew clean test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure \
    --no-configuration-cache
)

(
  cd "$ROOT/prototypes/vscode-workbench"
  if [[ "${CI:-}" == "true" || ! -d node_modules ]]; then
    npm ci
  fi
  npm test
  npm run test:web
  npm run package
)
