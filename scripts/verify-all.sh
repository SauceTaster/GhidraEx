#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_JAVA="$ROOT/.toolchains/jdk-25/Contents/Home"

if [[ -z "${JAVA_HOME:-}" && -x "$LOCAL_JAVA/bin/java" ]]; then
  export JAVA_HOME="$LOCAL_JAVA"
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JDK 25 is required. Set JAVA_HOME or install it at $LOCAL_JAVA." >&2
  exit 1
fi

export PATH="$JAVA_HOME/bin:$PATH"

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
  ./gradlew clean test buildPlugin --no-configuration-cache
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
